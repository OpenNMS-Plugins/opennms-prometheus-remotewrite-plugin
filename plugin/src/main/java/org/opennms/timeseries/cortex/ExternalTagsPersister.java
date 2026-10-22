/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.timeseries.cortex;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import org.json.JSONObject;
import org.opennms.integration.api.v1.distributed.KeyValueStore;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

/**
 * Keeps the external tags of every series in the key-value store, without ever making the
 * OpenNMS writer thread wait on the database.
 *
 * <p>The write path only has to know one thing per sample: does the stored record already hold
 * every external tag key of this sample? Values do not matter, because existing tags are never
 * overwritten. That answer is cached as a 64-bit fingerprint of (metric key, external tag keys)
 * in a fixed-size table - 8 bytes per slot, no objects, no GC pressure. A hit means there is
 * nothing to do. A miss hands the series to a small worker pool that does the database
 * read-merge-write and records the fingerprint on success. {@link #offer(Metric)} never blocks:
 * if the queue is full the series is skipped and picked up again on its next sample.
 *
 * <p>The table is 8-way set-associative with random replacement. Collection visits every series
 * once per interval in the same order, and under that cyclic pattern any deterministic policy -
 * the previous LRU cache, or a direct-mapped table - evicts each entry just before its next use
 * as soon as more series compete for a place than it holds. Eight candidate slots per series
 * keep the hit rate near 100% up to about half occupancy, and random replacement makes an
 * undersized table lose hits gradually instead of all at once. See "External tags" in the README
 * for sizing.
 */
public class ExternalTagsPersister implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalTagsPersister.class);

    /** Slots per bucket: 8 longs fill one 64-byte cache line. */
    static final int WAYS = 8;

    /** 1 GiB of table. A typo in the size must not allocate the heap away inside the bean constructor. */
    static final int MAX_TABLE_SLOTS = 1 << 27;

    /** Marks an empty slot. A real fingerprint of 0 is remapped, so it can never read as a hit. */
    private static final long EMPTY = 0L;

    private final KeyValueStore kvStore;
    private final AtomicLongArray table;
    private final int bucketMask;
    private final ThreadPoolExecutor executor;
    /** Keys queued or in progress, so a series is never checked by two workers at once. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean warmedUp;

    private final Meter extTagsModified;
    private final Meter extTagsCacheUsed;
    private final Meter extTagsCacheMissed;
    private final Meter extTagPutTransactionFailed;
    private final Meter extTagsDropped;

    /**
     * @param cacheSize slots in the fingerprint table, rounded up to a power of two and capped at
     *                  {@link #MAX_TABLE_SLOTS} and a quarter of the heap; 0 keeps no table, so
     *                  every sample is re-checked (asynchronously)
     * @param queueSize series that may wait for a worker before further ones are skipped
     * @param threads   workers talking to the key-value store
     * @param preload   seed the table from the stored records on startup
     */
    public ExternalTagsPersister(final KeyValueStore kvStore, final long cacheSize, final int queueSize,
                                 final int threads, final boolean preload, final MetricRegistry registry) {
        this.kvStore = Objects.requireNonNull(kvStore);

        final int slots = tableSlotsFor(cacheSize, Runtime.getRuntime().maxMemory());
        if (slots > 0) {
            this.table = new AtomicLongArray(slots);
            this.bucketMask = slots / WAYS - 1;
        } else {
            this.table = null;
            this.bucketMask = 0;
        }

        // Clamped rather than rejected: a throw here would fail the blueprint bean and take all
        // metric storage down over a typo in an optional tuning property.
        final int effectiveQueueSize = atLeastOne(queueSize, "externalTagsQueueSize");
        final int effectiveThreads = atLeastOne(threads, "externalTagsThreads");
        final AtomicInteger threadCount = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(effectiveThreads, effectiveThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(effectiveQueueSize),
                r -> {
                    final Thread thread = new Thread(r, "PrometheusExternalTags-" + threadCount.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());

        final MetricRegistry metrics = Objects.requireNonNull(registry);
        this.extTagsModified = metrics.meter("extTagsModified");
        this.extTagsCacheUsed = metrics.meter("extTagsCacheUsed");
        this.extTagsCacheMissed = metrics.meter("extTagsCacheMissed");
        this.extTagPutTransactionFailed = metrics.meter("extTagPutTransactionFailed");
        this.extTagsDropped = metrics.meter("extTagsDropped");
        metrics.register("extTagsQueueSize", (Gauge<Integer>) () -> executor.getQueue().size());

        if (preload) {
            warmUp();
        } else {
            warmedUp = true;
        }

        LOG.info("Started external tags persister: tableSlots={}, queueSize={}, threads={}, preload={}",
                slots, effectiveQueueSize, effectiveThreads, preload);
    }

    /**
     * The table size for a configured {@code cacheSize}: a power of two of at least one bucket,
     * never above {@link #MAX_TABLE_SLOTS} or a quarter of {@code maxHeapBytes}. Oversized values
     * are rounded down with a warning rather than allocated.
     */
    static int tableSlotsFor(final long cacheSize, final long maxHeapBytes) {
        if (cacheSize <= 0) {
            return 0;
        }
        final long heapLimit = Math.max(WAYS, Long.highestOneBit(Math.max(1, maxHeapBytes / 4 / Long.BYTES)));
        final long limit = Math.min(MAX_TABLE_SLOTS, heapLimit);
        if (cacheSize > limit) {
            LOG.warn("externalTagsCacheSize={} exceeds the largest table this JVM can afford ({} slots, {} MiB). "
                            + "Using {} slots.", cacheSize, limit, limit * Long.BYTES >> 20, limit);
            return (int) limit;
        }
        final long rounded = cacheSize <= WAYS ? WAYS : Long.highestOneBit(cacheSize - 1) << 1;
        return (int) Math.min(rounded, limit);
    }

    private static int atLeastOne(final int value, final String name) {
        if (value > 0) {
            return value;
        }
        LOG.warn("{}={} is not a positive number. Using 1.", name, value);
        return 1;
    }

    /**
     * Makes sure the stored record for this series carries all of its external tags. Returns
     * immediately; the database work, if any, happens on the persister's own threads.
     */
    public void offer(final Metric metric) {
        final Set<Tag> tags = metric.getExternalTags();
        if (tags.isEmpty()) {
            // Nothing to persist, and the read path treats a missing record as "no tags".
            return;
        }
        final String key = metric.getKey();
        int bucket = 0;
        long fingerprint = EMPTY;
        if (table != null) {
            final long keyHash = hash(key);
            long tagKeys = 0;
            for (Tag tag : tags) {
                tagKeys += hash(tag.getKey());
            }
            bucket = bucketOf(keyHash);
            fingerprint = fingerprint(keyHash, tagKeys);
            if (contains(bucket, fingerprint)) {
                extTagsCacheUsed.mark();
                return;
            }
        }

        if (!pending.add(key)) {
            return; // already on its way, and already counted
        }
        extTagsCacheMissed.mark();
        final Map<String, String> toPersist = new TreeMap<>();
        for (Tag tag : tags) {
            toPersist.putIfAbsent(tag.getKey(), tag.getValue());
        }
        final int b = bucket;
        final long fp = fingerprint;
        try {
            executor.execute(() -> reconcile(key, toPersist, b, fp));
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            extTagsDropped.mark();
        }
    }

    private void reconcile(final String key, final Map<String, String> tags, final int bucket, final long fingerprint) {
        try {
            final Optional<?> stored = kvStore.get(key, CortexTSS.CORTEX_TSS);
            final JSONObject record = stored.isPresent()
                    ? new JSONObject(stored.get().toString())
                    : new JSONObject();
            boolean changed = false;
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                // Tags are only ever added, never overwritten.
                if (!record.has(tag.getKey())) {
                    record.put(tag.getKey(), tag.getValue());
                    changed = true;
                }
            }
            if (changed) {
                kvStore.put(key, record.toString(), CortexTSS.CORTEX_TSS);
                if (stored.isPresent()) {
                    extTagsModified.mark();
                }
            }
            if (table != null) {
                insert(bucket, fingerprint);
            }
        } catch (RuntimeException e) {
            // Leave the table alone, so the next sample of this series tries again.
            extTagPutTransactionFailed.mark();
            LOG.debug("Exception occurred persisting external tags for metric: {}", key, e);
        } finally {
            pending.remove(key);
        }
    }

    /**
     * Seeds the table from the stored records, so a restart does not re-check every series. The
     * Integration API has no paged enumeration: the key-value store hands over every record of
     * the context in one map, and only then are they hashed and dropped. On a very large store
     * that map needs its own heap for the duration - see externalTagsPreload in the README.
     */
    @SuppressWarnings("unchecked")
    private void warmUp() {
        if (table == null) {
            warmedUp = true;
            return;
        }
        kvStore.enumerateContextAsync(CortexTSS.CORTEX_TSS).whenComplete((records, ex) -> {
            try {
                if (ex != null) {
                    LOG.warn("Could not preload the external tags; series will be checked as they are written.", ex);
                    return;
                }
                int loaded = 0;
                for (Map.Entry<String, Object> record : ((Map<String, Object>) records).entrySet()) {
                    try {
                        final JSONObject json = new JSONObject(record.getValue().toString());
                        if (json.isEmpty()) {
                            continue;
                        }
                        long tagKeys = 0;
                        for (String tagKey : json.keySet()) {
                            tagKeys += hash(tagKey);
                        }
                        final long keyHash = hash(record.getKey());
                        insert(bucketOf(keyHash), fingerprint(keyHash, tagKeys));
                        loaded++;
                    } catch (RuntimeException e) {
                        LOG.debug("Skipping unreadable external tags record {}", record.getKey(), e);
                    }
                }
                LOG.info("Preloaded {} external tag records.", loaded);
            } finally {
                warmedUp = true;
            }
        });
    }

    private boolean contains(final int bucket, final long fingerprint) {
        final int base = bucket * WAYS;
        for (int i = 0; i < WAYS; i++) {
            if (table.get(base + i) == fingerprint) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a fingerprint: in a free slot of its bucket if there is one, otherwise over a random
     * one. Random, because the cyclic collection order makes every deterministic victim choice
     * evict exactly the entry that is needed next.
     */
    private void insert(final int bucket, final long fingerprint) {
        if (contains(bucket, fingerprint)) {
            return;
        }
        final int base = bucket * WAYS;
        for (int i = 0; i < WAYS; i++) {
            if (table.get(base + i) == EMPTY && table.compareAndSet(base + i, EMPTY, fingerprint)) {
                return;
            }
        }
        table.set(base + ThreadLocalRandom.current().nextInt(WAYS), fingerprint);
    }

    private int bucketOf(final long keyHash) {
        return (int) (keyHash >>> 32) & bucketMask;
    }

    /**
     * Combines the key hash with the sum of the tag key hashes. The sum makes the result
     * independent of tag order, so neither side has to sort.
     */
    private static long fingerprint(final long keyHash, final long tagKeys) {
        final long fingerprint = mix(keyHash ^ mix(tagKeys + 0x9E3779B97F4A7C15L));
        return fingerprint == EMPTY ? 1L : fingerprint;
    }

    /** 64-bit FNV-1a over the UTF-16 chars, finished with the murmur3 mixer. Allocates nothing. */
    static long hash(final CharSequence value) {
        long h = 0xcbf29ce484222325L;
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                h ^= value.charAt(i);
                h *= 0x100000001b3L;
            }
        }
        return mix(h);
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb53a5d1b7ae9L;
        k ^= k >>> 33;
        return k;
    }

    boolean isWarmedUp() {
        return warmedUp;
    }

    int tableSlots() {
        return table == null ? 0 : table.length();
    }

    /** Test hook: waits until every queued series has been processed. */
    void awaitIdle() {
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("External tags workers did not stop within 5s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
