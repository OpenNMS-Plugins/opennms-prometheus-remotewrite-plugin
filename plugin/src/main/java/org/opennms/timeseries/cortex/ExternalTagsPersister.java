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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 * Keeps the external tags of every series in the key-value store, without ever making the
 * OpenNMS writer thread wait on the database.
 *
 * <p>The write path only has to know one thing per sample: is the stored record already a
 * superset of this sample's external tags? That answer is cached as a 64-bit fingerprint of
 * (metric key, external tags) in a fixed-size table indexed by the metric key - 8 bytes per slot,
 * no objects, no GC pressure. A hit means there is nothing to do. A miss hands the series to a
 * small worker pool that does the database read-merge-write and records the fingerprint on
 * success. {@link #offer(Metric)} never blocks: if the queue is full the series is skipped and
 * picked up again on its next sample.
 *
 * <p>The table is direct-mapped rather than LRU on purpose. Collection visits every series once
 * per interval in the same order, which made the previous LRU cache evict each entry just before
 * its next use as soon as the fleet outgrew it - a zero hit rate. Here a too-small table only
 * loses the series that collide, so the hit rate degrades in proportion instead of collapsing.
 */
public class ExternalTagsPersister implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalTagsPersister.class);

    /** Largest power-of-two table an AtomicLongArray can hold. */
    private static final int MAX_TABLE_SIZE = 1 << 30;

    /** Marks an empty slot. A real fingerprint of 0 is remapped, so it can never read as a hit. */
    private static final long EMPTY = 0L;

    private static final HashFunction FINGERPRINT = Hashing.murmur3_128();

    private final KeyValueStore kvStore;
    private final AtomicLongArray table;
    private final int mask;
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
     * @param cacheSize slots in the fingerprint table, rounded up to a power of two; 0 disables
     *                  the table, so every sample is re-checked (asynchronously)
     * @param queueSize series that may wait for a worker before further ones are skipped
     * @param threads   workers talking to the key-value store
     */
    public ExternalTagsPersister(final KeyValueStore kvStore, final long cacheSize, final int queueSize,
                                 final int threads, final MetricRegistry registry) {
        this.kvStore = Objects.requireNonNull(kvStore);
        if (queueSize <= 0) {
            throw new IllegalArgumentException("queueSize must be positive, got " + queueSize);
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive, got " + threads);
        }

        if (cacheSize > 0) {
            final int size = cacheSize >= MAX_TABLE_SIZE
                    ? MAX_TABLE_SIZE
                    : Integer.highestOneBit((int) Math.max(1, cacheSize - 1)) << 1;
            this.table = new AtomicLongArray(Math.max(1, size));
            this.mask = table.length() - 1;
        } else {
            this.table = null;
            this.mask = 0;
        }

        final AtomicInteger threadCount = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
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

        warmUp();

        LOG.info("Started external tags persister: tableSlots={}, queueSize={}, threads={}",
                table == null ? 0 : table.length(), queueSize, threads);
    }

    /**
     * Makes sure the stored record for this series carries all of its external tags. Returns
     * immediately; the database work, if any, happens on the persister's own threads.
     */
    public void offer(final Metric metric) {
        final Map<String, String> tags = tagsOf(metric);
        if (tags.isEmpty()) {
            // Nothing to persist, and the read path treats a missing record as "no tags".
            return;
        }
        final String key = metric.getKey();
        final int slot = slot(key);
        final long fingerprint = fingerprint(key, tags);
        if (table != null && table.get(slot) == fingerprint) {
            extTagsCacheUsed.mark();
            return;
        }
        extTagsCacheMissed.mark();

        if (!pending.add(key)) {
            return; // already on its way
        }
        try {
            executor.execute(() -> reconcile(key, tags, slot, fingerprint));
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            extTagsDropped.mark();
        }
    }

    private void reconcile(final String key, final Map<String, String> tags, final int slot, final long fingerprint) {
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
                table.set(slot, fingerprint);
            }
        } catch (RuntimeException e) {
            // Leave the slot alone, so the next sample of this series tries again.
            extTagPutTransactionFailed.mark();
            LOG.debug("Exception occurred persisting external tags for metric: {}", key, e);
        } finally {
            pending.remove(key);
        }
    }

    /**
     * Seeds the table from the stored records, so a restart does not re-check every series. Only
     * fingerprints are kept; the enumerated records are dropped as soon as they are hashed.
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
                        final Map<String, String> tags = new TreeMap<>();
                        new JSONObject(record.getValue().toString()).toMap()
                                .forEach((k, v) -> tags.put(k, String.valueOf(v)));
                        if (!tags.isEmpty()) {
                            // Never displace a fingerprint a worker set meanwhile.
                            table.compareAndSet(slot(record.getKey()), EMPTY, fingerprint(record.getKey(), tags));
                            loaded++;
                        }
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

    private static Map<String, String> tagsOf(final Metric metric) {
        final Map<String, String> tags = new TreeMap<>();
        for (Tag tag : metric.getExternalTags()) {
            tags.putIfAbsent(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private int slot(final String key) {
        final int h = key.hashCode() * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }

    /** Assumes {@code tags} iterates in key order, so equal tag sets hash equally. */
    private static long fingerprint(final String key, final Map<String, String> tags) {
        final Hasher hasher = FINGERPRINT.newHasher();
        putString(hasher, key);
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            putString(hasher, tag.getKey());
            putString(hasher, tag.getValue());
        }
        final long fingerprint = hasher.hash().asLong();
        return fingerprint == EMPTY ? 1L : fingerprint;
    }

    /** Length-prefixed, so ("ab","c") and ("a","bc") cannot collide. */
    private static void putString(final Hasher hasher, final String value) {
        final String s = value == null ? "" : value;
        hasher.putInt(s.length()).putString(s, UTF_8);
    }

    boolean isWarmedUp() {
        return warmedUp;
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
