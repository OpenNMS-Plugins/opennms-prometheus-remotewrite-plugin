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
package org.opennms.timeseries.cortex.batch;

import org.opennms.integration.api.v1.timeseries.StorageException;

/**
 * A write failure that applies to the whole request, not to any one series inside it: bad
 * credentials, the wrong tenant, the wrong endpoint. Unlike a plain {@link StorageException},
 * which {@link ShardedWriteBatcher} treats as plausibly naming one bad series and bisects to
 * isolate, this is dropped whole: every bisected half would fail identically, so isolation could
 * only spend up to one request per series confirming that, stalling the shard while its queue
 * fills behind it.
 */
public class NonIsolableWriteException extends StorageException {

    private static final long serialVersionUID = 1L;

    public NonIsolableWriteException(final String message) {
        super(message);
    }
}
