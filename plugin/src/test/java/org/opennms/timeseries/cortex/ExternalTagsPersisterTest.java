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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;

import com.codahale.metrics.MetricRegistry;

public class ExternalTagsPersisterTest {

    private static final long GIB = 1L << 30;

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
    public void aChangedTagValueNeedsNoDatabaseWork() {
        // Values are never overwritten, so a renamed ifAlias has nothing to persist.
        persister = persister(1024, 100);
        persister.offer(metric("if1", "ifAlias", "uplink"));
        persister.awaitIdle();

        persister.offer(metric("if1", "ifAlias", "core"));
        persister.awaitIdle();

        assertEquals(1, kvStore.gets.get());
        assertEquals(1, meter("extTagsCacheUsed"));
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
    public void countsASeriesThatIsAlreadyPendingAsOneMiss() {
        // Under database latency a fast series offers many samples while its check is pending;
        // counting each as a miss would read as "table too small" when the database is slow.
        kvStore.block();
        persister = persister(1024, 100);
        Metric metric = metric("if1", "ifAlias", "uplink");

        for (int i = 0; i < 5; i++) {
            persister.offer(metric);
        }

        assertEquals(1, meter("extTagsCacheMissed"));
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
    public void theWarmUpMatchesSamplesWhoseTagValuesChangedSinceTheyWereStored() {
        Metric metric = metric("if1", "ifAlias", "core");
        kvStore.seed(metric.getKey(), "{\"ifAlias\":\"uplink\"}");
        kvStore.enumerate.set(true);
        persister = persister(1024, 100);
        await().atMost(Duration.ofSeconds(5)).until(persister::isWarmedUp);

        persister.offer(metric);
        persister.awaitIdle();

        assertEquals(0, kvStore.gets.get());
    }

    @Test
    public void skipsTheWarmUpWhenPreloadIsDisabled() {
        kvStore.enumerate.set(true);
        persister = new ExternalTagsPersister(kvStore, 1024, 100, 1, false, registry);

        assertTrue(persister.isWarmedUp());
        assertEquals(0, kvStore.enumerations.get());
    }

    @Test
    public void keepsNearlyEveryHitWhenTheTableIsTwiceTheFleet() {
        // The access pattern collection produces: every series once per interval, same order.
        // A direct-mapped table of the same size hits only ~60% here.
        double hitRate = steadyStateHitRate(1024, 512);
        assertTrue("hit rate at half occupancy was " + hitRate, hitRate >= 0.9);
    }

    @Test
    public void degradesGraduallyWhenTheFleetOutgrowsTheTable() {
        // 1.5x oversubscribed: no cache can exceed 67% here. Random replacement keeps ~41%; the
        // previous direct-mapped table kept ~23% and the LRU cache before it 0%.
        double hitRate = steadyStateHitRate(1024, 1536);
        assertTrue("hit rate at 1.5x oversubscription was " + hitRate, hitRate >= 0.32);
    }

    @Test
    public void withoutATableEverySeriesIsCheckedButNeverBlocks() {
        persister = persister(0, 100);
        Metric metric = metric("if1", "ifAlias", "uplink");
        persister.offer(metric);
        persister.awaitIdle();
        persister.offer(metric);
        persister.awaitIdle();

        assertEquals(0, persister.tableSlots());
        assertEquals(2, kvStore.gets.get());
        assertEquals(1, kvStore.puts.get());
    }

    @Test
    public void roundsTheTableUpToWholeBuckets() {
        assertEquals(0, ExternalTagsPersister.tableSlotsFor(0, 4 * GIB));
        assertEquals(0, ExternalTagsPersister.tableSlotsFor(-1, 4 * GIB));
        assertEquals(ExternalTagsPersister.WAYS, ExternalTagsPersister.tableSlotsFor(1, 4 * GIB));
        assertEquals(1024, ExternalTagsPersister.tableSlotsFor(1000, 4 * GIB));
        assertEquals(1024, ExternalTagsPersister.tableSlotsFor(1024, 4 * GIB));
    }

    @Test
    public void capsAnOversizedTableInsteadOfAllocatingTheHeapAway() {
        assertEquals("never above 1 GiB of table", ExternalTagsPersister.MAX_TABLE_SLOTS,
                ExternalTagsPersister.tableSlotsFor(10_000_000_000L, 64 * GIB));
        assertEquals("never above a quarter of the heap", (int) (GIB / 4 / Long.BYTES),
                ExternalTagsPersister.tableSlotsFor(10_000_000_000L, GIB));
        assertEquals("rounding up must not cross the cap either", (int) (GIB / 4 / Long.BYTES),
                ExternalTagsPersister.tableSlotsFor(GIB / 4 / Long.BYTES + 1, GIB));

        persister = persister(Long.MAX_VALUE, 100);
        assertTrue(persister.tableSlots() <= ExternalTagsPersister.MAX_TABLE_SLOTS);
    }

    @Test
    public void clampsANonPositiveQueueSizeAndThreadCount() {
        persister = new ExternalTagsPersister(kvStore, 1024, 0, 0, false, registry);
        Metric metric = metric("if1", "ifAlias", "uplink");

        persister.offer(metric);
        persister.awaitIdle();

        assertEquals("{\"ifAlias\":\"uplink\"}", kvStore.raw(metric.getKey()));
    }

    @Test
    public void closeStopsTheWorkers() {
        persister = persister(1024, 100);
        persister.close();
        persister.offer(metric("if1", "ifAlias", "uplink"));
        assertEquals(0, kvStore.gets.get());
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private ExternalTagsPersister persister(long cacheSize, int queueSize) {
        return new ExternalTagsPersister(kvStore, cacheSize, queueSize, 1, true, registry);
    }

    /** Offers the fleet in the same order for a few intervals, then measures one more. */
    private double steadyStateHitRate(long tableSlots, int series) {
        persister = persister(tableSlots, series);
        Metric[] fleet = new Metric[series];
        for (int i = 0; i < series; i++) {
            fleet[i] = metric("if" + i, "ifAlias", "a" + i);
        }
        for (int round = 0; round < 4; round++) {
            for (Metric metric : fleet) {
                persister.offer(metric);
            }
            persister.awaitIdle(); // an interval is long enough for the workers to catch up
        }
        long hitsBefore = meter("extTagsCacheUsed");
        for (Metric metric : fleet) {
            persister.offer(metric);
        }
        persister.awaitIdle();
        return (meter("extTagsCacheUsed") - hitsBefore) / (double) series;
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
}
