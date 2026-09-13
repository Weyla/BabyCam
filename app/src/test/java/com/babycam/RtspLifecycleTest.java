package com.babycam;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RtspLifecycleTest {
    @Test public void lastViewerDisconnectsAndPortCanBeReused() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        RtspServer.Listener listener = new RtspServer.Listener() {
            @Override public void onStreamError(RtspServer source, String message, Throwable error) { }
            @Override public void onPlayingClientCountChanged(RtspServer source, int count) {
                if (count == 1) joined.countDown();
                if (count == 0) left.countDown();
            }
        };
        RtspServer.setLocalIpv4Address("127.0.0.1");
        RtspServer server = new RtspServer(false, "cam", "", port, listener);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                request(socket, input, "SETUP rtsp://127.0.0.1/live/trackID=1 RTSP/1.0\r\nCSeq: 1\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n");
                request(socket, input, "PLAY rtsp://127.0.0.1/live RTSP/1.0\r\nCSeq: 2\r\n\r\n");
                assertTrue(joined.await(2, TimeUnit.SECONDS));
                assertEquals(1, server.getPlayingClientCount());
                request(socket, input, "TEARDOWN rtsp://127.0.0.1/live RTSP/1.0\r\nCSeq: 3\r\n\r\n");
                assertTrue(left.await(2, TimeUnit.SECONDS));
                assertEquals(0, server.getPlayingClientCount());
            }
            server.stop();
            server = new RtspServer(false, "cam", "", port, listener);
            server.start();
            try (Socket reconnected = new Socket("127.0.0.1", port)) {
                assertTrue(reconnected.isConnected());
            }
        } finally {
            server.stop();
            RtspServer.setLocalIpv4Address(null);
        }
    }

    private static void request(Socket socket, BufferedReader input, String request) throws Exception {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        assertEquals("RTSP/1.0 200 OK", input.readLine());
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) { }
    }
}
