/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.mediadriver;

import org.junit.*;
import uk.co.real_logic.aeron.mediadriver.buffer.BufferRotator;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.logbuffer.*;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBufferDescriptor;
import uk.co.real_logic.aeron.util.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.util.protocol.HeaderFlyweight;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.util.BitUtil.align;

/**
 * Test Sender in isolation
 */
public class SenderTest
{
    public static final long LOG_BUFFER_SIZE = (64*1024) + RingBufferDescriptor.TRAILER_LENGTH;
    public static final int MAX_FRAME_LENGTH = 1024;
    public static final long SESSION_ID = 1L;
    public static final long CHANNEL_ID = 2L;
    public static final long INITIAL_TERM_ID = 3L;
    public static final byte[] PAYLOAD = "Payload is here!".getBytes();

    private final Sender sender = new Sender();
    private final BufferRotator rotator = BufferAndFrameUtils.createTestRotator(LOG_BUFFER_SIZE,
            LogBufferDescriptor.STATE_BUFFER_LENGTH);
    private final LogAppender[] logAppenders = BufferAndFrameUtils.createLogAppenders(rotator, MAX_FRAME_LENGTH);

    private SenderChannel channel;
    private final ControlFrameHandler mockFrameHandler = mock(ControlFrameHandler.class);
    private final SenderControlStrategy spySenderControlStrategy = spy(new UnicastSenderControlStrategy());

    private long currentTimestamp;

    private final Queue<ByteBuffer> receivedFrames = new ArrayDeque<>();

    private final UdpDestination destination = UdpDestination.parse("udp://localhost:40123");
    private final InetSocketAddress recvAddr = destination.remoteData();

    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight();

    private SenderChannel.SendFunction sendFunction = (buffer, addr) ->
    {
        assertThat(addr, is(recvAddr));

        final int size = buffer.limit() - buffer.position();
        final ByteBuffer savedFrame = ByteBuffer.allocate(size);
        savedFrame.put(buffer);
        receivedFrames.add(savedFrame);

        return size;
    };

    private SenderChannel.TimeFunction timeFunction = () -> currentTimestamp;

    @Before
    public void setUp()
    {
        currentTimestamp = 0;

        when(mockFrameHandler.destination()).thenReturn(destination);

        channel = new SenderChannel(mockFrameHandler, spySenderControlStrategy, rotator, SESSION_ID,
                CHANNEL_ID, INITIAL_TERM_ID, logAppenders[0].defaultHeaderLength(), MAX_FRAME_LENGTH,
                sendFunction, timeFunction);
        sender.addChannel(channel);
    }

    @Test
    public void shouldAddAndRemoveSenderChannel()
    {
        sender.removeChannel(channel);
    }

    @Test
    public void shouldSend0LengthDataFrameOnChannelWhenTimeoutWithoutStatusMessage()
    {
        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.INITIAL_HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(0L));
    }

    @Test
    public void shouldSendMultiple0LengthDataFrameOnChannelWhenTimeoutWithoutStatusMessage()
    {
        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.INITIAL_HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(0L));

        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.INITIAL_HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.termOffset(), is(0L));
    }

    @Test
    public void shouldNotSend0LengthDataFrameAfterReceivingStatusMessage()
    {
        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.HEARTBEAT_TIMEOUT_MS);
        channel.onStatusMessage(INITIAL_TERM_ID, 0, 0);
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));
    }

    @Test
    public void shouldBeAbleToSendOnChannel()
    {
        channel.onStatusMessage(INITIAL_TERM_ID, 0, align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT));

        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
    }

    @Test
    public void shouldBeAbleToSendOnChannelTwice()
    {
        channel.onStatusMessage(INITIAL_TERM_ID, 0, (2 * align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT)));

        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();
        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(2));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);  // first frame

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);  // second frame

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    @Test
    @Ignore("Does not work currently. SenderChannel needs to use gather sends. DatagramChannel.write(ByteBuffer[])")
    public void shouldBeAbleToSendOnChannelTwiceAsBatch()
    {
        channel.onStatusMessage(INITIAL_TERM_ID, 0, (2 * align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT)));

        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should send now

        final AtomicBuffer receivedBuffer = new AtomicBuffer(receivedFrames.remove());

        dataHeader.wrap(receivedBuffer, 0);  // first frame

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));

        dataHeader.wrap(receivedBuffer, DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length);  // second frame

        assertThat(dataHeader.version(), is(Short.valueOf(HeaderFlyweight.CURRENT_VERSION)));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.channelId(), is(CHANNEL_ID));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    @Test
    public void shouldNotSendUntilStatusMessageReceived()
    {
        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);
        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));

        sender.process();
        assertThat(receivedFrames.size(), is(0));  // should not send as no SM

        channel.onStatusMessage(INITIAL_TERM_ID, 0, align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should send now
    }

    @Test
    public void shouldNotBeAbleToSendAfterUsingUpYourWindow()
    {
        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);
        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        channel.onStatusMessage(INITIAL_TERM_ID, 0, align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT));

        sender.process();
        assertThat(receivedFrames.size(), is(1));  // should send now

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should not send now
    }

    @Test
    public void shouldSend0LengthDataFrameAsHeartbeatWhenIdle()
    {
        channel.onStatusMessage(INITIAL_TERM_ID, 0, align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT));

        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should send now

        receivedFrames.remove();                   // skip data frame

        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    @Test
    public void shouldSendMultiple0LengthDataFrameAsHeartbeatsWhenIdle()
    {
        channel.onStatusMessage(INITIAL_TERM_ID, 0, align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT));

        final AtomicBuffer buffer = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        assertTrue(logAppenders[0].append(buffer, 0, PAYLOAD.length));
        sender.process();

        assertThat(receivedFrames.size(), is(1));  // should send now

        receivedFrames.remove();                   // skip data frame

        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));

        currentTimestamp += TimeUnit.MILLISECONDS.toNanos(SenderChannel.HEARTBEAT_TIMEOUT_MS) - 1;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.heartbeatChecks();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send now

        dataHeader.wrap(new AtomicBuffer(receivedFrames.remove()), 0);

        assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    private long offsetOfMessage(final int num)
    {
        return (num - 1) * align(PAYLOAD.length, FrameDescriptor.FRAME_ALIGNMENT);
    }
}
