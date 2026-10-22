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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe key-value store for the external tags tests: counts database operations, can
 * block reads to stand in for an unresponsive database, and can fail writes.
 */
class CountingKVStore extends KVStoreMock {
    private final Map<String, Object> store = new ConcurrentHashMap<>();
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger puts = new AtomicInteger();
    final AtomicInteger enumerations = new AtomicInteger();
    /** Simulates a failing database for every put while set. */
    final AtomicBoolean failPuts = new AtomicBoolean();
    /** Whether the warm-up enumeration returns the stored records or an empty map. */
    final AtomicBoolean enumerate = new AtomicBoolean();
    private final CountDownLatch gate = new CountDownLatch(1);
    private volatile boolean blocked;

    void seed(String key, String json) {
        store.put(key, json);
    }

    String raw(String key) {
        Object value = store.get(key);
        return value == null ? null : value.toString();
    }

    /** Makes every read wait until {@link #release()}: a database that never answers. */
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
        enumerations.incrementAndGet();
        return CompletableFuture.completedFuture(enumerate.get() ? new ConcurrentHashMap<>(store) : Map.of());
    }
}
