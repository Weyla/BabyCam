package com.babycam;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import android.content.Intent;
import android.os.Handler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.mockito.MockedConstruction;
import android.content.SharedPreferences;

public class VideoDemandTest {
    private MockedConstruction<Intent> intents;
    @Before public void setupIntents() {
        intents = mockConstruction(Intent.class, withSettings().defaultAnswer(RETURNS_SELF));
    }
    @After public void closeIntents() { intents.close(); }

    @Test public void videoDemandTracksMultipleViewersAndLeavesAudioConnected() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        LinkedBlockingQueue<Integer> demands = new LinkedBlockingQueue<>();
        RtspServer.Listener listener = new RtspServer.Listener() {
            @Override public void onStreamError(RtspServer s, String message, Throwable e) { }
            @Override public void onPlayingClientCountChanged(RtspServer s, int count) { }
            @Override public void onVideoDemandChanged(RtspServer s) {
                demands.offer(s.getVideoViewerCount());
            }
        };
        RtspServer.setLocalIpv4Address("127.0.0.1");
        RtspServer server = new RtspServer(true, "cam", "", port, listener);
        try {
            server.start();
            try (Viewer audio = new Viewer(port, 1);
                 Viewer firstVideo = new Viewer(port, 0);
                 Viewer secondVideo = new Viewer(port, 0)) {
                audio.command("PLAY");
                assertEquals(Integer.valueOf(0), demands.poll(2, TimeUnit.SECONDS));
                firstVideo.command("PLAY");
                assertEquals(Integer.valueOf(1), demands.poll(2, TimeUnit.SECONDS));
                secondVideo.command("PLAY");
                assertEquals(Integer.valueOf(2), demands.poll(2, TimeUnit.SECONDS));
                firstVideo.command("TEARDOWN");
                assertEquals(Integer.valueOf(1), demands.poll(2, TimeUnit.SECONDS));
                secondVideo.command("PAUSE");
                assertEquals(Integer.valueOf(0), demands.poll(2, TimeUnit.SECONDS));
                // The original audio session still responds, without another SETUP/PLAY.
                audio.command("GET_PARAMETER");
                secondVideo.command("PLAY");
                assertEquals(Integer.valueOf(1), demands.poll(2, TimeUnit.SECONDS));
                secondVideo.command("TEARDOWN");
                assertEquals(Integer.valueOf(0), demands.poll(2, TimeUnit.SECONDS));
                audio.command("GET_PARAMETER");
                assertEquals(0, server.getVideoViewerCount());
            }
        } finally {
            server.stop();
            RtspServer.setLocalIpv4Address(null);
        }
    }

    @Test public void lastVideoViewerReleasesCaptureButPreservesAudioAndServer() throws Exception {
        RtspCameraService service = mock(RtspCameraService.class);
        RtspServer server = mock(RtspServer.class);
        CameraController camera = mock(CameraController.class);
        H264Encoder video = mock(H264Encoder.class);
        AacEncoder audio = mock(AacEncoder.class);
        field(service, "server", server);
        field(service, "running", true);
        field(service, "currentVideoEnabled", true);
        field(service, "cameraController", camera);
        field(service, "videoEncoder", video);
        field(service, "audioEncoder", audio);
        when(server.hasVideoConfiguration()).thenReturn(true);
        try {
            when(server.getVideoViewerCount()).thenReturn(1);
            update(service, server);
            verify(camera, never()).stop();
            verify(video, never()).stop();
            when(server.getVideoViewerCount()).thenReturn(0);
            update(service, server);
            verify(camera).stop();
            verify(video).stop();
            verify(server).clearCachedVideo();
            verify(server, never()).stop();
            verifyNoInteractions(audio);
            assertTrue(RtspCameraService.isRunning());
            assertTrue(RtspCameraService.isVideoEnabled());

            SharedPreferences settings = mock(SharedPreferences.class);
            when(service.getSharedPreferences(eq(AppSettings.PREFS), anyInt())).thenReturn(settings);
            when(server.getVideoViewerCount()).thenReturn(1);
            try (MockedConstruction<H264Encoder> encoders = mockConstruction(H264Encoder.class);
                 MockedConstruction<CameraController> cameras = mockConstruction(CameraController.class)) {
                update(service, server);
                verify(encoders.constructed().get(0)).start();
                verify(cameras.constructed().get(0)).start();
                verifyNoInteractions(audio);
                verify(server, never()).stop();
            }
        } finally { field(service, "running", false); }
    }

    @Test public void cameraFailureDoesNotDisconnectAnAudioViewer() throws Exception {
        RtspCameraService service = mock(RtspCameraService.class);
        RtspServer server = mock(RtspServer.class);
        CameraController camera = mock(CameraController.class);
        H264Encoder video = mock(H264Encoder.class);
        AacEncoder audio = mock(AacEncoder.class);
        Handler handler = mock(Handler.class);
        when(handler.post(any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return true;
        });
        field(service, "mainHandler", handler);
        field(service, "server", server);
        field(service, "running", true);
        field(service, "currentVideoEnabled", true);
        field(service, "cameraController", camera);
        field(service, "videoEncoder", video);
        field(service, "audioEncoder", audio);
        doCallRealMethod().when(service).onCameraError(any(), anyString(), any());
        doCallRealMethod().when(service).captureMessage();
        try {
            service.onCameraError(camera, "Camera disconnected", new IllegalStateException());
            verify(camera).stop();
            verify(video).stop();
            verifyNoInteractions(audio);
            verify(server, never()).stop();
            verify(service, never()).stopSelf();
            assertTrue(RtspCameraService.isRunning());
            assertEquals("Audio is live • Video unavailable", service.captureMessage());
        } finally { field(service, "running", false); }
    }

    @Test public void retiredCameraErrorCannotStopNewCamera() throws Exception {
        RtspCameraService service = mock(RtspCameraService.class);
        CameraController current = mock(CameraController.class);
        Handler handler = mock(Handler.class);
        when(handler.post(any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return true;
        });
        field(service, "mainHandler", handler);
        field(service, "cameraController", current);
        doCallRealMethod().when(service).onCameraError(any(), anyString(), any());
        service.onCameraError(mock(CameraController.class), "Old camera error", null);
        verifyNoInteractions(current);
    }

    @Test public void encoderErrorsDistinguishVideoFromAudio() {
        RtspServer.Listener listener = mock(RtspServer.Listener.class);
        RtspServer server = new RtspServer(true, "cam", "", 8554, listener);
        server.onVideoError("video", null);
        verify(listener).onVideoStreamError(server, "video", null);
        verify(listener, never()).onStreamError(any(), anyString(), any());
        server.onAudioError("audio", null);
        verify(listener).onStreamError(server, "audio", null);
    }

    @Test public void statusDescribesCaptureRatherThanVideoCapability() throws Exception {
        RtspCameraService service = mock(RtspCameraService.class);
        field(service, "currentVideoEnabled", true);
        doCallRealMethod().when(service).captureMessage();
        assertEquals("Audio is live • Camera sleeping (no video viewers)", service.captureMessage());
        field(service, "cameraController", mock(CameraController.class));
        field(service, "videoEncoder", mock(H264Encoder.class));
        assertTrue(service.captureMessage().startsWith("Video and audio are live"));
    }

    private static void update(RtspCameraService service, RtspServer server) throws Exception {
        Method method = RtspCameraService.class.getDeclaredMethod("updateVideoDemand", RtspServer.class);
        method.setAccessible(true);
        method.invoke(service, server);
    }

    private static void field(Object service, String name, Object value) throws Exception {
        Field field = RtspCameraService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static final class Viewer implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        int sequence;
        Viewer(int port, int track) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(2000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            request("SETUP", "/live/trackID=" + track,
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        }
        void command(String method) throws Exception { request(method, "/live", ""); }
        void request(String method, String path, String headers) throws Exception {
            String request = method + " rtsp://127.0.0.1" + path + " RTSP/1.0\r\nCSeq: "
                    + (++sequence) + "\r\n" + headers + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            assertEquals("RTSP/1.0 200 OK", input.readLine());
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) { }
        }
        @Override public void close() throws Exception { socket.close(); }
    }
}
