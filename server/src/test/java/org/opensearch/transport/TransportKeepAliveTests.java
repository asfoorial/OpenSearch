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

package org.opensearch.transport;

import org.opensearch.common.AsyncBiFunction;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TransportKeepAliveTests extends OpenSearchTestCase {

    private final ConnectionProfile defaultProfile = ConnectionProfile.buildDefaultConnectionProfile(Settings.EMPTY);
    private BytesReference expectedPingMessage;
    private AsyncBiFunction<TcpChannel, BytesReference, Void> pingSender;
    private TransportKeepAlive keepAlive;
    private CapturingThreadPool threadPool;

    @Override
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        super.setUp();
        pingSender = mock(AsyncBiFunction.class);
        threadPool = new CapturingThreadPool();
        keepAlive = new TransportKeepAlive(threadPool, pingSender);

        try (BytesStreamOutput out = new BytesStreamOutput()) {
            out.writeByte((byte) 'E');
            out.writeByte((byte) 'S');
            out.writeInt(-1);
            expectedPingMessage = out.bytes();
        } catch (IOException e) {
            throw new AssertionError(e.getMessage(), e); // won't happen
        }
    }

    @Override
    public void tearDown() throws Exception {
        threadPool.shutdown();
        super.tearDown();
    }

    public void testRegisterNodeConnectionSchedulesKeepAlive() {
        TimeValue pingInterval = TimeValue.timeValueSeconds(randomLongBetween(1, 60));
        ConnectionProfile connectionProfile = new ConnectionProfile.Builder(defaultProfile)
            .setPingInterval(pingInterval)
            .build();

        assertEquals(0, threadPool.scheduledTasks.size());

        TcpChannel channel1 = new FakeTcpChannel();
        TcpChannel channel2 = new FakeTcpChannel();
        channel1.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel2.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        keepAlive.registerNodeConnection(Arrays.asList(channel1, channel2), connectionProfile);

        assertEquals(1, threadPool.scheduledTasks.size());
        Tuple<TimeValue, Runnable> taskTuple = threadPool.scheduledTasks.poll();
        assertEquals(pingInterval, taskTuple.v1());
        Runnable keepAliveTask = taskTuple.v2();
        assertEquals(0, threadPool.scheduledTasks.size());
        keepAliveTask.run();

        verify(pingSender, times(1)).apply(same(channel1), eq(expectedPingMessage), any());
        verify(pingSender, times(1)).apply(same(channel2), eq(expectedPingMessage), any());

        // Test that the task has rescheduled itself
        assertEquals(1, threadPool.scheduledTasks.size());
        Tuple<TimeValue, Runnable> rescheduledTask = threadPool.scheduledTasks.poll();
        assertEquals(pingInterval, rescheduledTask.v1());
    }

    public void testRegisterMultipleKeepAliveIntervals() {
        TimeValue pingInterval1 = TimeValue.timeValueSeconds(randomLongBetween(1, 30));
        ConnectionProfile connectionProfile1 = new ConnectionProfile.Builder(defaultProfile)
            .setPingInterval(pingInterval1)
            .build();

        TimeValue pingInterval2 = TimeValue.timeValueSeconds(randomLongBetween(31, 60));
        ConnectionProfile connectionProfile2 = new ConnectionProfile.Builder(defaultProfile)
            .setPingInterval(pingInterval2)
            .build();

        assertEquals(0, threadPool.scheduledTasks.size());

        TcpChannel channel1 = new FakeTcpChannel();
        TcpChannel channel2 = new FakeTcpChannel();
        channel1.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel2.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        keepAlive.registerNodeConnection(Collections.singletonList(channel1), connectionProfile1);
        keepAlive.registerNodeConnection(Collections.singletonList(channel2), connectionProfile2);

        assertEquals(2, threadPool.scheduledTasks.size());
        Tuple<TimeValue, Runnable> taskTuple1 = threadPool.scheduledTasks.poll();
        Tuple<TimeValue, Runnable> taskTuple2 = threadPool.scheduledTasks.poll();
        assertEquals(pingInterval1, taskTuple1.v1());
        assertEquals(pingInterval2, taskTuple2.v1());
        Runnable keepAliveTask1 = taskTuple1.v2();
        Runnable keepAliveTask2 = taskTuple1.v2();

        assertEquals(0, threadPool.scheduledTasks.size());
        keepAliveTask1.run();
        assertEquals(1, threadPool.scheduledTasks.size());
        keepAliveTask2.run();
        assertEquals(2, threadPool.scheduledTasks.size());
    }

    public void testClosingChannelUnregistersItFromKeepAlive() {
        TimeValue pingInterval1 = TimeValue.timeValueSeconds(randomLongBetween(1, 30));
        ConnectionProfile connectionProfile = new ConnectionProfile.Builder(defaultProfile)
            .setPingInterval(pingInterval1)
            .build();

        TcpChannel channel1 = new FakeTcpChannel();
        TcpChannel channel2 = new FakeTcpChannel();
        channel1.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel2.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        keepAlive.registerNodeConnection(Collections.singletonList(channel1), connectionProfile);
        keepAlive.registerNodeConnection(Collections.singletonList(channel2), connectionProfile);

        channel1.close();

        Runnable task = threadPool.scheduledTasks.poll().v2();
        task.run();

        verify(pingSender, times(0)).apply(same(channel1), eq(expectedPingMessage), any());
        verify(pingSender, times(1)).apply(same(channel2), eq(expectedPingMessage), any());
    }

    public void testKeepAliveResponseIfServer() {
        TcpChannel channel = new FakeTcpChannel(true);
        channel.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());

        keepAlive.receiveKeepAlive(channel);

        verify(pingSender, times(1)).apply(same(channel), eq(expectedPingMessage), any());
    }

    public void testNoKeepAliveResponseIfClient() {
        TcpChannel channel = new FakeTcpChannel(false);
        channel.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());

        keepAlive.receiveKeepAlive(channel);

        verify(pingSender, times(0)).apply(same(channel), eq(expectedPingMessage), any());
    }

    public void testOnlySendPingIfWeHaveNotWrittenAndReadSinceLastPing() {
        TimeValue pingInterval = TimeValue.timeValueSeconds(15);
        ConnectionProfile connectionProfile = new ConnectionProfile.Builder(defaultProfile)
            .setPingInterval(pingInterval)
            .build();

        TcpChannel channel1 = new FakeTcpChannel();
        TcpChannel channel2 = new FakeTcpChannel();
        channel1.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel2.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        keepAlive.registerNodeConnection(Arrays.asList(channel1, channel2), connectionProfile);

        Tuple<TimeValue, Runnable> taskTuple = threadPool.scheduledTasks.poll();
        taskTuple.v2().run();

        TcpChannel.ChannelStats stats = channel1.getChannelStats();
        stats.markAccessed(threadPool.relativeTimeInMillis() + (pingInterval.millis() / 2));

        taskTuple = threadPool.scheduledTasks.poll();
        taskTuple.v2().run();

        verify(pingSender, times(1)).apply(same(channel1), eq(expectedPingMessage), any());
        verify(pingSender, times(2)).apply(same(channel2), eq(expectedPingMessage), any());
    }

    private class CapturingThreadPool extends TestThreadPool {

        private final Deque<Tuple<TimeValue, Runnable>> scheduledTasks = new ArrayDeque<>();

        private CapturingThreadPool() {
            super(getTestName());
        }

        @Override
        public ScheduledCancellable schedule(Runnable task, TimeValue delay, String executor) {
            scheduledTasks.add(new Tuple<>(delay, task));
            return null;
        }
    }
}
