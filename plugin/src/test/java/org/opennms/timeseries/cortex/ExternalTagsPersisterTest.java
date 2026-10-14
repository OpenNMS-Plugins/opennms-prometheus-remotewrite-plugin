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

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;

import com.codahale.metrics.MetricRegistry;

public class ExternalTagsPersisterTest {

    private final CountingKVStore kvStore = new CountingKVStore();
    private final MetricRegistry registry = new MetricRegistry();
    private ExternalTagsPersister persister;

    @After
    public void tearDown() {
        kvStore.release();
        if (persister != null) {
            persister.close();
        }
    }

    @Test
    public void ignoresSeriesWithoutExternalTags() {
        persister = persister(1024, 100);

        for (int i = 0; i < 10; i++) {
            persister.offer(metric("if" + i));
        }
        persister.awaitIdle();

        assertEquals("a series without external tags has nothing to persist", 0, kvStore.gets.get());
        assertEquals(0, kvStore.puts.get());
    }

    @Test
    public void createsANewRecordOnceAndThenNeverTouchesTheDatabaseAgain() {
        persister = persister(1024, 100);
        Metric metric = metric("if1", "ifAlias", "uplink");

        persister.offer(metric);
        persister.awaitIdle();
        assertEquals(1, kvStore.gets.get());
        assertEquals(1, kvStore.puts.get());
        assertEquals("{\"ifAlias\":\"uplink\"}", kvStore.raw(metric.getKey()));

        for (int i = 0; i < 100; i++) {
            persister.offer(metric);
        }
        persister.awaitIdle();
        assertEquals("a confirmed series must not read the database again", 1, kvStore.gets.get());
        assertEquals(1, kvStore.puts.get());
        assertEquals(100, meter("extTagsCacheUsed"));
    }

    @Test
    public void doesNotRewriteACompleteRecord() {
        Metric metric = metric("if1", "ifAlias", "uplink");
        kvStore.seed(metric.getKey(), "{\"ifAlias\":\"uplink\",\"ifDescr\":\"Ethernet1\"}");
        persister = persister(1024, 100);

        persister.offer(metric);
        persister.offer(metric);
        persister.awaitIdle();
        persister.offer(metric);
        persister.awaitIdle();

        assertEquals(1, kvStore.gets.get());
        assertEquals("a record already holding every tag must never be re-written", 0, kvStore.puts.get());
        assertEquals(0, meter("extTagsModified"));
    }

    @Test
    public void mergesANewTagWithoutOverwritingExistingOnes() {
        persister = persister(1024, 100);
        persister.offer(metric("if1", "ifAlias", "uplink"));
        persister.awaitIdle();

        persister.offer(metric("if1", "ifAlias", "renamed", "ifDescr", "Ethernet1"));
        persister.awaitIdle();

        JSONObject stored = new JSONObject(kvStore.raw(metric("if1").getKey()));
        assertEquals("existing tags are never overwritten", "uplink", stored.getString("ifAlias"));
        assertEquals("Ethernet1", stored.getString("ifDescr"));
        assertEquals(2, kvStore.puts.get());
        assertEquals(1, meter("extTagsModified"));

        persister.offer(metric("if1", "ifAlias", "renamed", "ifDescr", "Ethernet1"));
        persister.awaitIdle();
        assertEquals("the merged tag set is confirmed after one check", 2, kvStore.gets.get());
    }

    @Test
    public void neverBlocksTheCallerOnTheDatabase() {
        kvStore.block();
        persister = persister(1024, 2);

        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            persister.offer(metric("if" + i, "ifAlias", "a" + i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("offer() must not wait on the key-value store, took " + elapsedMs + "ms", elapsedMs < 1_000);
        assertTrue("a full queue must drop instead of block", meter("extTagsDropped") > 0);
    }

    @Test
    public void retriesASeriesWhoseWriteFailed() {
        kvStore.failPuts.set(true);
        persister = persister(1024, 100);
        Metric metric = metric("if1", "ifAlias", "uplink");

        persister.offer(metric);
        persister.awaitIdle();
        assertEquals(1, meter("extTagPutTransactionFailed"));

        kvStore.failPuts.set(false);
        persister.offer(metric);
        persister.awaitIdle();
        assertEquals("a failed write must not be cached as persisted", "{\"ifAlias\":\"uplink\"}",
                kvStore.raw(metric.getKey()));
    }

    @Test
    public void warmsUpFromTheStoredRecords() {
        Metric metric = metric("if1", "ifAlias", "uplink");
        kvStore.seed(metric.getKey(), "{\"ifAlias\":\"uplink\"}");
        kvStore.enumerate.set(true);
        persister = persister(1024, 100);
        await().atMost(Duration.ofSeconds(5)).until(persister::isWarmedUp);

        persister.offer(metric);
        persister.awaitIdle();

        assertEquals("a series already stored must be confirmed by the warm-up", 0, kvStore.gets.get());
        assertEquals(1, meter("extTagsCacheUsed"));
    }

    @Test
    public void keepsServingHitsWhenTheTableIsSmallerThanTheFleet() {
        // The old LRU cache dropped to a zero hit rate as soon as the fleet outgrew it, because
        // collection visits every series once per interval in the same order.
        persister = persister(64, 10_000);
        int series = 256;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < series; i++) {
                persister.offer(metric("if" + i, "ifAlias", "a" + i));
            }
            persister.awaitIdle();
        }
        assertTrue("expected cache hits from a table smaller than the fleet, got none",
                meter("extTagsCacheUsed") > 0);
    }

    @Test
    public void withoutATableEverySeriesIsCheckedButNeverBlocks() {
        persister = persister(0, 100);
        Metric metric = metric("if1", "ifAlias", "uplink");
        persister.offer(metric);
        persister.awaitIdle();
        persister.offer(metric);
        persister.awaitIdle();

        assertEquals(2, kvStore.gets.get());
        assertEquals(1, kvStore.puts.get());
    }

    @Test
    public void closeStopsTheWorkers() {
        persister = persister(1024, 100);
        persister.close();
        persister.offer(metric("if1", "ifAlias", "uplink"));
        assertFalse(kvStore.gets.get() > 0);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private ExternalTagsPersister persister(long cacheSize, int queueSize) {
        return new ExternalTagsPersister(kvStore, cacheSize, queueSize, 1, registry);
    }

    private long meter(String name) {
        return registry.meter(name).getCount();
    }

    private static Metric metric(String resource, String... externalTags) {
        ImmutableMetric.MetricBuilder builder = ImmutableMetric.builder()
                .intrinsicTag("resourceId", "snmp/1/" + resource)
                .intrinsicTag("name", "ifHCInOctets")
                .metaTag("mtype", Metric.Mtype.counter.name());
        for (int i = 0; i < externalTags.length; i += 2) {
            builder.externalTag(externalTags[i], externalTags[i + 1]);
        }
        return builder.build();
    }

    private static final class CountingKVStore extends KVStoreMock {
        private final Map<String, Object> store = new ConcurrentHashMap<>();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicBoolean failPuts = new AtomicBoolean();
        private final AtomicBoolean enumerate = new AtomicBoolean();
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean blocked;

        void seed(String key, String json) {
            store.put(key, json);
        }

        String raw(String key) {
            Object value = store.get(key);
            return value == null ? null : value.toString();
        }

        void block() {
            blocked = true;
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Optional get(String key, String context) {
            gets.incrementAndGet();
            if (blocked) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public long put(String key, Object value, String context) {
            if (failPuts.get()) {
                throw new RuntimeException("simulated database failure");
            }
            puts.incrementAndGet();
            store.put(key, value);
            return 0L;
        }

        @Override
        public CompletableFuture<Long> putAsync(String key, Object value, String context) {
            throw new AssertionError("the persister writes synchronously on its own threads");
        }

        @Override
        public CompletableFuture<Map> enumerateContextAsync(String context) {
            return CompletableFuture.completedFuture(enumerate.get() ? new ConcurrentHashMap<>(store) : Map.of());
        }
    }
}
