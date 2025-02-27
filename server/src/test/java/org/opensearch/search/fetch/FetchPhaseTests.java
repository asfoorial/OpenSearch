/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.fetch;

import org.opensearch.test.OpenSearchTestCase;

public class FetchPhaseTests extends OpenSearchTestCase {
    public void testSequentialDocs() {
        FetchPhase.DocIdToIndex[] docs = new FetchPhase.DocIdToIndex[10];
        int start = randomIntBetween(0, Short.MAX_VALUE);
        for (int i = 0; i < 10; i++) {
            docs[i] = new FetchPhase.DocIdToIndex(start, i);
            ++ start;
        }
        assertTrue(FetchPhase.hasSequentialDocs(docs));

        int from = randomIntBetween(0, 9);
        start = docs[from].docId;
        for (int i = from; i < 10; i++) {
            start += randomIntBetween(2, 10);
            docs[i] = new FetchPhase.DocIdToIndex(start, i);
        }
        assertFalse(FetchPhase.hasSequentialDocs(docs));
    }
}
