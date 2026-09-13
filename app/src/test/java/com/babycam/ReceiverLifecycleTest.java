package com.babycam;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import androidx.media3.common.PlaybackException;
import androidx.media3.session.MediaSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class ReceiverLifecycleTest {
    private MockedConstruction<Intent> intents;
    private MockedConstruction<Notification.Builder> notifications;
    private MockedConstruction<Notification.Action.Builder> actions;
    private MockedStatic<PendingIntent> pending;
    private ReceiverService service;
    private Handler handler;

    @Before public void setup() throws Exception {
        intents = mockConstruction(Intent.class, withSettings().defaultAnswer(RETURNS_SELF));
        notifications = mockConstruction(Notification.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        actions = mockConstruction(Notification.Action.Builder.class);
        pending = mockStatic(PendingIntent.class);
        service = mock(ReceiverService.class);
        handler = mock(Handler.class);
        field("mainHandler", handler);
        field("running", true);
        field("currentStatus", ReceiverService.STATUS_PLAYING);
    }

    @After public void cleanup() throws Exception {
        field("running", false);
        field("currentStatus", ReceiverService.STATUS_STOPPED);
        pending.close();
        actions.close();
        notifications.close();
        intents.close();
    }

    @Test public void pausePublishesPausedAndSuppressesReconnectErrors() throws Exception {
        Runnable alarm = mock(Runnable.class);
        field("alarmRunnable", alarm);
        field("outageStartedAt", 1000L);
        doCallRealMethod().when(service).onPlayWhenReadyChanged(anyBoolean(), anyInt());
        doCallRealMethod().when(service).onPlayerError(any());
        service.onPlayWhenReadyChanged(false, 1);
        assertEquals(ReceiverService.STATUS_PAUSED, ReceiverService.getCurrentStatus());
        assertEquals(0L, read("outageStartedAt"));
        verify(handler).removeCallbacks(alarm);
        service.onPlayerError(mock(PlaybackException.class));
        verify(handler, never()).postDelayed(any(), anyLong());
    }

    @Test public void emptySessionNotificationDoesNotDemoteActiveMonitor() throws Exception {
        doCallRealMethod().when(service).onUpdateNotificationAsync(any(), anyBoolean());
        assertNull(service.onUpdateNotificationAsync(mock(MediaSession.class), false).get());
        verify(service).startForeground(eq(8555), nullable(Notification.class));
        verify(service, never()).stopForeground(anyInt());
    }

    @Test public void disablingAlarmStopsSoundDuringExistingOutage() throws Exception {
        SharedPreferences settings = mock(SharedPreferences.class);
        when(service.getSharedPreferences(eq(AppSettings.PREFS), anyInt())).thenReturn(settings);
        when(settings.getBoolean(AppSettings.KEY_ALARM_ENABLED, false)).thenReturn(false);
        MediaPlayer alarm = mock(MediaPlayer.class);
        field("alarmPlayer", alarm);
        field("outageStartedAt", 1000L);
        java.lang.reflect.Method apply = ReceiverService.class.getDeclaredMethod("applyAlarmSettings");
        apply.setAccessible(true);
        apply.invoke(service);
        verify(alarm).stop();
        verify(alarm).release();
        assertNull(read("alarmPlayer"));
        verify(handler, never()).postDelayed(any(), anyLong());
    }

    private void field(String name, Object value) throws Exception {
        Field field = ReceiverService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
    private Object read(String name) throws Exception {
        Field field = ReceiverService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }
}
