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
 *    http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.index.reindex;

import org.opensearch.Version;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.AutoCreateIndex;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.AliasMetadata;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.common.Nullable;
import org.opensearch.common.bytes.BytesArray;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.indices.SystemIndices;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashMap;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests source and target index validation of reindex. Mostly that means testing that indexing from an index back into itself fails the
 * request. Note that we can't catch you trying to remotely reindex from yourself into yourself. We actually assert here that reindexes
 * from remote don't need to come from existing indexes. It'd be silly to fail requests if the source index didn't exist on the target
 * cluster....
 */
public class ReindexSourceTargetValidationTests extends OpenSearchTestCase {
    private static final ClusterState STATE = ClusterState.builder(new ClusterName("test")).metadata(Metadata.builder()
                .put(index("target", "target_alias", "target_multi"), true)
                .put(index("target2", "target_multi"), true)
                .put(index("target_with_write_index", true, "target_multi_with_write_index"), true)
                .put(index("target2_without_write_index", "target_multi_with_write_index"), true)
                .put(index("qux", false, "target_alias_with_write_index_disabled"), true)
                .put(index("foo"), true)
                .put(index("bar"), true)
                .put(index("baz"), true)
                .put(index("source", "source_multi"), true)
                .put(index("source2", "source_multi"), true)).build();
    private static final IndexNameExpressionResolver INDEX_NAME_EXPRESSION_RESOLVER =
        new IndexNameExpressionResolver(new ThreadContext(Settings.EMPTY));
    private static final AutoCreateIndex AUTO_CREATE_INDEX = new AutoCreateIndex(Settings.EMPTY,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), INDEX_NAME_EXPRESSION_RESOLVER,
            new SystemIndices(new HashMap<>()));

    private final BytesReference query = new BytesArray("{ \"foo\" : \"bar\" }");

    public void testObviousCases() {
        fails("target", "target");
        fails("target", "foo", "bar", "target", "baz");
        fails("target", "foo", "bar", "target", "baz", "target");
        succeeds("target", "source");
        succeeds("target", "source", "source2");
    }

    public void testAliasesContainTarget() {
        fails("target", "target_alias");
        fails("target_alias", "target");
        fails("target", "foo", "bar", "target_alias", "baz");
        fails("target_alias", "foo", "bar", "target_alias", "baz");
        fails("target_alias", "foo", "bar", "target", "baz");
        fails("target", "foo", "bar", "target_alias", "target_alias");
        fails("target", "target_multi");
        fails("target", "foo", "bar", "target_multi", "baz");
        succeeds("target", "source_multi");
        succeeds("target", "source", "source2", "source_multi");
    }

    public void testTargetIsAliasToMultipleIndicesWithoutWriteAlias() {
        Exception e = expectThrows(IllegalArgumentException.class, () -> succeeds("target_multi", "foo"));
        assertThat(e.getMessage(), containsString("no write index is defined for alias [target_multi]. The write index may be explicitly " +
                "disabled using is_write_index=false or the alias points to multiple indices without one being designated as a " +
                "write index"));
    }

    public void testTargetIsAliasWithWriteIndexDisabled() {
        Exception e = expectThrows(IllegalArgumentException.class, () -> succeeds("target_alias_with_write_index_disabled", "foo"));
        assertThat(e.getMessage(), containsString("no write index is defined for alias [target_alias_with_write_index_disabled]. " +
            "The write index may be explicitly disabled using is_write_index=false or the alias points to multiple indices without one " +
            "being designated as a write index"));
        succeeds("qux", "foo"); // writing directly into the index of which this is the alias works though
    }

    public void testTargetIsWriteAlias() {
        succeeds("target_multi_with_write_index", "foo");
        succeeds("target_multi_with_write_index", "target2_without_write_index");
        fails("target_multi_with_write_index", "target_multi_with_write_index");
        fails("target_multi_with_write_index", "target_with_write_index");
    }

    public void testRemoteInfoSkipsValidation() {
        // The index doesn't have to exist
        succeeds(new RemoteInfo(randomAlphaOfLength(5), "test", 9200, null, query, null, null, emptyMap(),
                RemoteInfo.DEFAULT_SOCKET_TIMEOUT, RemoteInfo.DEFAULT_CONNECT_TIMEOUT), "does_not_exist", "target");
        // And it doesn't matter if they are the same index. They are considered to be different because the remote one is, well, remote.
        succeeds(new RemoteInfo(randomAlphaOfLength(5), "test", 9200, null, query, null, null, emptyMap(),
                RemoteInfo.DEFAULT_SOCKET_TIMEOUT, RemoteInfo.DEFAULT_CONNECT_TIMEOUT), "target", "target");
    }

    private void fails(String target, String... sources) {
        Exception e = expectThrows(ActionRequestValidationException.class, () -> succeeds(target, sources));
        assertThat(e.getMessage(), containsString("reindex cannot write into an index its reading from"));
    }

    private void succeeds(String target, String... sources) {
        succeeds(null, target, sources);
    }

    private void succeeds(RemoteInfo remoteInfo, String target, String... sources) {
        ReindexValidator.validateAgainstAliases(new SearchRequest(sources), new IndexRequest(target), remoteInfo,
                INDEX_NAME_EXPRESSION_RESOLVER, AUTO_CREATE_INDEX, STATE);
    }

    private static IndexMetadata index(String name, String... aliases) {
        return index(name, null, aliases);
    }

    private static IndexMetadata index(String name, @Nullable Boolean writeIndex, String... aliases) {
        IndexMetadata.Builder builder = IndexMetadata.builder(name).settings(Settings.builder()
                .put("index.version.created", Version.CURRENT.id)
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 1));
        for (String alias: aliases) {
            builder.putAlias(AliasMetadata.builder(alias).writeIndex(writeIndex).build());
        }
        return builder.build();
    }
}
