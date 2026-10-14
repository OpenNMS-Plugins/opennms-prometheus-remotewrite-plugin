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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * The write path must never wait on the key-value store (PNNMS-13): before, every cache miss cost
 * a synchronous database read on the OpenNMS writer thread, which capped write throughput at
 * writer_threads / database latency.
 */
public class ExternalTagsCacheTest {

    private MockWebServer server;
    private CountingKVStore kvStore;
    private CortexTSS tss;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        kvStore = new CountingKVStore();
    }

    @After
    public void tearDown() throws Exception {
        kvStore.gate.countDown();
        if (tss != null) {
            tss.destroy();
            tss = null;
        }
        server.shutdown();
    }

    @Test
    public void storeDoesNotWaitOnTheKeyValueStore() throws Exception {
        kvStore.blocked = true; // a database that never answers
        tss = storage(CortexTSSConfig.builder());

        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            server.enqueue(new MockResponse().setResponseCode(200));
            tss.store(List.of(sample(metric("if" + i, "uplink"), t, i)));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("store() waited on the key-value store for " + elapsedMs + "ms", elapsedMs < 5_000);
        assertEquals(20, meter("samplesWritten"));
    }

    @Test
    public void persistsTheTagsAndThenServesEveryLookupFromTheCache() throws Exception {
        Metric metric = metric("if1", "uplink");
        tss = storage(CortexTSSConfig.builder());

        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        server.enqueue(new MockResponse().setResponseCode(200));
        tss.store(List.of(sample(metric, t, 0)));
        await().atMost(Duration.ofSeconds(5)).until(() -> kvStore.store.containsKey(metric.getKey()));

        for (int i = 1; i <= 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(200));
            tss.store(List.of(sample(metric, t.plusSeconds(i), i)));
        }

        assertEquals("{\"ifAlias\":\"uplink\"}", kvStore.store.get(metric.getKey()));
        assertEquals("only the first sample may read the database", 1, kvStore.gets.get());
        assertEquals(1, kvStore.puts.get());
        assertEquals(3, meter("extTagsCacheUsed"));
    }

    @Test
    public void disabledExternalTagsNeverTouchTheKeyValueStore() throws Exception {
        tss = storage(CortexTSSConfig.builder().externalTagsEnabled(false));

        server.enqueue(new MockResponse().setResponseCode(200));
        tss.store(List.of(sample(metric("if1", "uplink"), Instant.now(), 1)));

        assertEquals(0, kvStore.gets.get());
        assertEquals(0, kvStore.puts.get());
        assertEquals(0, kvStore.enumerations.get());
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private CortexTSS storage(CortexTSSConfig.Builder builder) {
        return new CortexTSS(builder
                .writeUrl(server.url("/api/prom/push").toString())
                .readUrl(server.url("/prometheus/api/v1").toString())
                .build(), kvStore);
    }

    private long meter(String name) {
        return tss.getMetrics().meter(name).getCount();
    }

    private static Metric metric(String resource, String ifAlias) {
        return ImmutableMetric.builder()
                .intrinsicTag("resourceId", "snmp/1/" + resource)
                .intrinsicTag("name", "ifHCInOctets")
                .metaTag("mtype", Metric.Mtype.counter.name())
                .externalTag("ifAlias", ifAlias)
                .build();
    }

    private static Sample sample(Metric metric, Instant time, double value) {
        return ImmutableSample.builder().metric(metric).time(time).value(value).build();
    }

    private static final class CountingKVStore extends KVStoreMock {
        private final Map<String, Object> store = new ConcurrentHashMap<>();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger enumerations = new AtomicInteger();
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean blocked;

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
            puts.incrementAndGet();
            store.put(key, value);
            return 0L;
        }

        @Override
        public CompletableFuture<Map> enumerateContextAsync(String context) {
            enumerations.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of());
        }
    }
}
