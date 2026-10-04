package com.babycam;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import org.junit.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StandbyRecoveryTest {
    private MockedConstruction<Intent> intents;
    private MockedConstruction<Notification.Builder> notifications;
    private MockedStatic<PendingIntent> pending;
    private RtspCameraService service;
    private SharedPreferences settings;
    private Handler handler;
    private PowerManager.WakeLock lock;
    private Runnable timeout;

    @Before public void setup() throws Exception {
        intents = mockConstruction(Intent.class, withSettings().defaultAnswer(RETURNS_SELF));
        notifications = mockConstruction(Notification.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        pending = mockStatic(PendingIntent.class);
        service = mock(RtspCameraService.class);
        settings = mock(SharedPreferences.class);
        when(service.getSharedPreferences(eq(AppSettings.PREFS), anyInt())).thenReturn(settings);
        handler = mock(Handler.class);
        when(handler.post(any())).thenAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return true; });
        lock = mock(PowerManager.WakeLock.class);
        timeout = mock(Runnable.class);
        field("mainHandler", handler);
        field("wakeLock", lock);
        field("standbyDisconnectTimeout", timeout);
        field("running", false);
        field("currentVideoEnabled", true);
        field("armed", false);
        field("currentPort", AppSettings.DEFAULT_STREAM_PORT);
        doCallRealMethod().when(service).onPlayingClientCountChanged(any(), anyInt());
        doCallRealMethod().when(service).captureMessage();
    }

    @After public void cleanup() throws Exception {
        field("running", false);
        field("currentVideoEnabled", true);
        field("armed", false);
        field("currentPort", AppSettings.DEFAULT_STREAM_PORT);
        pending.close(); notifications.close(); intents.close();
    }

    @Test public void captureShutdownReleasesWakeLockEvenWhenStandbyIsEnabled() throws Exception {
        when(settings.getBoolean(AppSettings.KEY_ARMED_ENABLED, false)).thenReturn(true);
        when(lock.isHeld()).thenReturn(true);
        invoke("stopStreaming");
        verify(lock).release();
        assertFalse(RtspCameraService.isRunning());
    }

    @Test public void idleStandbyListenerDoesNotAcquireACpuWakeLock() throws Exception {
        when(settings.getBoolean(AppSettings.KEY_ARMED_ENABLED, false)).thenReturn(true);
        when(settings.getString(AppSettings.KEY_STREAM_PASSWORD, "")).thenReturn("standby-password");
        try (MockedConstruction<ArmedControl.Server> controls = mockConstruction(ArmedControl.Server.class)) {
            invoke("ensureControlServer");
            verify(controls.constructed().get(0)).start();
            verifyNoInteractions(lock);
        }
    }

    @Test public void reconnectDuringGraceKeepsTheStreamAndExpiryChecksCurrentDemand() throws Exception {
        RtspServer server = connectedStandby();
        service.onPlayingClientCountChanged(server, 0);
        verify(handler).postDelayed(timeout, RtspCameraService.STANDBY_RECONNECT_GRACE_MS);
        verify(server, never()).stop();
        when(server.getPlayingClientCount()).thenReturn(1);
        service.onPlayingClientCountChanged(server, 1);
        verify(handler, atLeastOnce()).removeCallbacks(timeout);
        // Even a callback already dequeued at reconnection must check the current count.
        invoke("finishStandbyDisconnect");
        assertTrue(RtspCameraService.isRunning());
        verify(server, never()).stop();
    }

    @Test public void graceExpiryStopsCaptureAndReleasesWakeLock() throws Exception {
        RtspServer server = connectedStandby();
        when(lock.isHeld()).thenReturn(true);
        service.onPlayingClientCountChanged(server, 0);
        invoke("finishStandbyDisconnect");
        verify(server).stop();
        verify(lock).release();
        assertFalse(RtspCameraService.isRunning());
    }

    @Test public void callbacksFromRetiredServersCannotScheduleStandbyShutdown() throws Exception {
        connectedStandby();
        service.onPlayingClientCountChanged(mock(RtspServer.class), 0);
        verify(handler, never()).postDelayed(any(), anyLong());
    }

    @Test public void initialVideoFailureKeepsHealthyAudioAndOmitsVideo() throws Exception {
        field("startupGeneration", new AtomicLong(1));
        try (MockedStatic<RtspServer> addresses = mockStatic(RtspServer.class);
             MockedConstruction<RtspServer> servers = mockConstruction(RtspServer.class);
             MockedConstruction<AacEncoder> audio = mockConstruction(AacEncoder.class);
             MockedConstruction<H264Encoder> video = mockConstruction(H264Encoder.class,
                     (encoder, context) -> doThrow(new IOException("Unsupported video format")).when(encoder).start())) {
            addresses.when(RtspServer::getLocalIpv4Address).thenReturn("192.168.1.20");
            Method start = RtspCameraService.class.getDeclaredMethod("startStreaming", boolean.class, int.class, long.class);
            start.setAccessible(true);
            start.invoke(service, true, 720, 1L);
            verify(lock).acquire();
            verify(audio.constructed().get(0)).start();
            verify(audio.constructed().get(0), never()).stop();
            verify(servers.constructed().get(0), never()).stop();
            verify(servers.constructed().get(0)).disableVideo();
            assertTrue(RtspCameraService.isRunning());
            assertFalse(RtspCameraService.isVideoEnabled());
            assertEquals("Audio is live • Video unavailable", service.captureMessage());
        }
    }

    @Test public void activeControlsKeepTheRtspConfigurationUntilTheStreamStops() throws Exception {
        field("running", true);
        field("streamConfiguration", new ControlConfiguration("192.168.1.20", 8555, "old-user", "old-password"));
        when(settings.getInt(AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT)).thenReturn(9554);
        when(settings.getString(AppSettings.KEY_STREAM_USERNAME, AppSettings.DEFAULT_USERNAME)).thenReturn("new-user");
        when(settings.getString(AppSettings.KEY_STREAM_PASSWORD, "")).thenReturn("new-password");
        try (MockedConstruction<ArmedControl.Server> controls = mockConstruction(ArmedControl.Server.class,
                (server, context) -> {
                    assertEquals(8555, context.arguments().get(0));
                    assertEquals("old-user", context.arguments().get(1));
                    assertEquals("old-password", context.arguments().get(2));
                })) {
            invoke("ensureControlServer");
            assertEquals(8554, RtspCameraService.getCurrentPort());
            verify(controls.constructed().get(0)).start();
        }
    }

    private RtspServer connectedStandby() throws Exception {
        RtspServer server = mock(RtspServer.class);
        field("server", server);
        field("running", true);
        field("standbyStartedStream", true);
        field("standbyViewerConnected", true);
        return server;
    }

    private void invoke(String name) throws Exception {
        Method method = RtspCameraService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(service);
    }

    private void field(String name, Object value) throws Exception {
        Field field = RtspCameraService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
