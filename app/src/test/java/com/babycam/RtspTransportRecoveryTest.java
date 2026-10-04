package com.babycam;

import org.junit.Test;
import java.io.*;
import java.net.Socket;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RtspTransportRecoveryTest {
    @Test public void pauseAndRepeatedPlayCountOnlyActivePlayback() throws Exception {
        RtspServer.Listener listener = mock(RtspServer.Listener.class);
        RtspServer server = new RtspServer(false, "cam", "", 8554, listener);
        Socket socket = mock(Socket.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String setup = "SETUP rtsp://127.0.0.1/live/trackID=1 RTSP/1.0\r\nCSeq: 1\r\n"
                + "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n";
        String requests = setup + request("PLAY", 2) + request("PLAY", 3)
                + request("PAUSE", 4) + request("PAUSE", 5) + request("PLAY", 6);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(requests.getBytes(StandardCharsets.US_ASCII)));
        when(socket.getOutputStream()).thenReturn(output);
        Object client = client(server, socket);
        ((Runnable) client).run(); // EOF closes the resumed session.
        org.mockito.InOrder order = inOrder(listener);
        order.verify(listener).onPlayingClientCountChanged(server, 1);
        order.verify(listener).onPlayingClientCountChanged(server, 0);
        order.verify(listener).onPlayingClientCountChanged(server, 1);
        order.verify(listener).onPlayingClientCountChanged(server, 0);
        verify(listener, times(2)).onPlayingClientCountChanged(server, 1);
        verify(listener, times(2)).onPlayingClientCountChanged(server, 0);
        assertEquals(0, server.getPlayingClientCount());
        assertEquals(6, output.toString(StandardCharsets.US_ASCII).split("200 OK", -1).length - 1);
    }

    @Test public void largeKeyframeSurvivesTemporaryWriterStallAndDrainsCompletely() throws Exception {
        RtspServer server = new RtspServer(true, "cam", "", 8554, mock(RtspServer.Listener.class));
        CountDownLatch blocked = new CountDownLatch(1), resume = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicInteger packets = new AtomicInteger();
        byte[] idr = new byte[310_000]; idr[0] = 0x65;
        int expectedPackets = 1 + (idr.length - 1 + 1197) / 1198;
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException {
                blocked.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer timeout"); }
                catch (InterruptedException error) { throw new IOException(error); }
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                if (packets.incrementAndGet() == expectedPackets) drained.countDown();
            }
        };
        Socket socket = socket(output);
        doAnswer(call -> { resume.countDown(); return null; }).when(socket).close();
        Object client = client(server, socket);
        set(client, "playing", true);
        Class<?> transport = Class.forName("com.babycam.RtspServer$TrackTransport");
        Method tcp = transport.getDeclaredMethod("tcp", boolean.class, int.class, int.class);
        tcp.setAccessible(true);
        set(client, "video", tcp.invoke(null, false, 0, 1));
        try {
            server.onVideoAccessUnit(Collections.singletonList(new byte[]{0x65, 1}), 0, true, false);
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            server.onVideoAccessUnit(Collections.singletonList(idr), 50_000, true, false);
            verify(socket, never()).close();
            resume.countDown();
            assertTrue(drained.await(2, TimeUnit.SECONDS));
            assertEquals(expectedPackets, packets.get());
            verify(socket, never()).close();
        } finally {
            resume.countDown();
            ((Closeable) client).close();
        }
    }

    @Test public void watchdogClosesOnlyPersistentlyStalledClients() throws Exception {
        RtspServer.Listener listener = mock(RtspServer.Listener.class);
        RtspServer server = new RtspServer(false, "cam", "", 8554, listener);
        Socket socket = socket(new ByteArrayOutputStream());
        Object client = client(server, socket);
        Method check = client.getClass().getDeclaredMethod("checkWriteTimeout", long.class);
        check.setAccessible(true);
        try {
            check.invoke(client, 30_000_000_000L); // Idle writers have no timeout.
            set(client, "writeProgressNs", 1_000_000_000L);
            check.invoke(client, 10_999_999_999L);
            verify(socket, never()).close();
            check.invoke(client, 11_000_000_000L);
            verify(socket).close();
            verify(listener).onClientEvent(server, "write_timeout");
        } finally { ((Closeable) client).close(); }
    }

    @Test public void videoStartupFailureAdvertisesAudioAndRejectsVideoSetup() throws Exception {
        RtspServer server = new RtspServer(true, "cam", "", 8554, mock(RtspServer.Listener.class));
        server.disableVideo();
        String requests = request("DESCRIBE", 1)
                + "SETUP rtsp://127.0.0.1/live/trackID=0 RTSP/1.0\r\nCSeq: 2\r\n"
                + "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Socket socket = socket(output);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(requests.getBytes(StandardCharsets.US_ASCII)));
        ((Runnable) client(server, socket)).run();
        String response = output.toString(StandardCharsets.US_ASCII);
        assertTrue(response.contains("m=audio"));
        assertFalse(response.contains("m=video"));
        assertTrue(response.contains("RTSP/1.0 404 Not Found"));
    }

    private static String request(String method, int sequence) {
        return method + " rtsp://127.0.0.1/live RTSP/1.0\r\nCSeq: " + sequence + "\r\n\r\n";
    }
    private static Socket socket(OutputStream output) throws IOException {
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(socket.getOutputStream()).thenReturn(output);
        return socket;
    }
    @SuppressWarnings("unchecked")
    private static Object client(RtspServer server, Socket socket) throws Exception {
        Class<?> type = Class.forName("com.babycam.RtspServer$Client");
        Constructor<?> constructor = type.getDeclaredConstructor(RtspServer.class, Socket.class);
        constructor.setAccessible(true);
        Object client = constructor.newInstance(server, socket);
        Field clients = RtspServer.class.getDeclaredField("clients");
        clients.setAccessible(true);
        ((Collection<Object>) clients.get(server)).add(client);
        return client;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
