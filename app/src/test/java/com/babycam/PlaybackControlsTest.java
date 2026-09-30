package com.babycam;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackControlsTest {
    private ReceiverService service;
    private ExoPlayer audio;
    private ExoPlayer video;
    private SharedPreferences.Editor editor;
    private SharedPreferences settings;
    private MockedConstruction<Intent> intents;
    private MockedConstruction<Notification.Builder> notifications;
    private MockedConstruction<Notification.Action.Builder> actions;
    private MockedStatic<PendingIntent> pending;

    @Before public void setup() throws Exception {
        intents = mockConstruction(Intent.class, withSettings().defaultAnswer(RETURNS_SELF));
        notifications = mockConstruction(Notification.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        actions = mockConstruction(Notification.Action.Builder.class);
        pending = mockStatic(PendingIntent.class);
        service = mock(ReceiverService.class);
        audio = mock(ExoPlayer.class);
        video = mock(ExoPlayer.class);
        field("player", audio);
        field("videoPlayer", video);
        field("mainHandler", mock(Handler.class));
        field("running", true);
        field("videoOutputVisible", true);
        field("screenInteractive", true);
        field("hasVideo", true);
        field("listenOnly", false);
        field("talking", false);
        field("currentStatus", ReceiverService.STATUS_PLAYING);
        field("streamUri", "rtsp://192.168.1.10:8554/live");
        settings = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(service.getSharedPreferences(eq(AppSettings.PREFS), anyInt())).thenReturn(settings);
        when(settings.edit()).thenReturn(editor);
        doCallRealMethod().when(service).setListenOnly(anyBoolean());
        doCallRealMethod().when(service).setPlaybackVolume(anyInt());
        doCallRealMethod().when(service).setVideoOutputVisible(anyBoolean());
        doCallRealMethod().when(service).setScreenInteractive(anyBoolean());
    }

    @After public void cleanup() throws Exception {
        field("running", false);
        field("hasVideo", false);
        field("listenOnly", false);
        field("talking", false);
        field("currentStatus", ReceiverService.STATUS_STOPPED);
        pending.close(); actions.close(); notifications.close(); intents.close();
    }

    @Test public void switchingToAudioTearsDownOnlyVideo() {
        service.setListenOnly(true);
        assertTrue(ReceiverService.isListenOnly());
        assertFalse(ReceiverService.hasVideo());
        assertTrue(ReceiverService.isVideoAvailable());
        verify(video).stop();
        verify(video).clearMediaItems();
        verifyNoInteractions(audio);
        verify(editor).putBoolean(AppSettings.KEY_LISTEN_ONLY, true);
    }

    @Test public void switchingBackToVideoStartsOnlyVideo() throws Exception {
        field("listenOnly", true);
        try (MockedStatic<MediaItem> items = mockStatic(MediaItem.class);
             MockedConstruction<RtspMediaSource.Factory> factories = mockConstruction(
                     RtspMediaSource.Factory.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            service.setListenOnly(false);
            assertTrue(ReceiverService.hasVideo());
            verify(video).prepare();
            verify(video).play();
            verifyNoInteractions(audio);
            assertEquals(1, factories.constructed().size());
        }
    }

    @Test public void audioOnlySourceCannotEnableVideo() throws Exception {
        field("hasVideo", false);
        field("listenOnly", true);
        service.setListenOnly(false);
        assertTrue(ReceiverService.isListenOnly());
        verifyNoInteractions(video, audio, editor);
    }

    @Test public void screenOffStopsVideoWithoutTouchingAudioOrModePreference() throws Exception {
        field("videoPrepared", true);
        field("outageStartedAt", 0L);
        service.setScreenInteractive(false);
        verify(video).stop();
        verify(video).clearMediaItems();
        verifyNoInteractions(audio, editor);
        assertFalse(ReceiverService.isListenOnly());
        assertEquals(ReceiverService.STATUS_PLAYING, ReceiverService.getCurrentStatus());
        assertEquals(0L, read("outageStartedAt"));
        assertEquals(false, read("videoPrepared"));
    }

    @Test public void hiddenOutputPreventsVideoRetriesEvenWithScreenOn() throws Exception {
        service.setVideoOutputVisible(false);
        clearInvocations(video);
        service.setListenOnly(false);
        verify(video, never()).prepare();
        verify(video, never()).play();
        verifyNoInteractions(audio);
    }

    @Test public void returningToVisibleVideoRestartsOnlyVideo() throws Exception {
        service.setScreenInteractive(false);
        service.setVideoOutputVisible(false);
        clearInvocations(video);
        service.setScreenInteractive(true);
        verify(video, never()).prepare();
        try (MockedStatic<MediaItem> items = mockStatic(MediaItem.class);
             MockedConstruction<RtspMediaSource.Factory> factories = mockConstruction(
                     RtspMediaSource.Factory.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            service.setVideoOutputVisible(true);
            verify(video).prepare();
            verify(video).play();
            verifyNoInteractions(audio);
            assertEquals(1, factories.constructed().size());
        }
    }

    @Test public void screenCycleInAudioModeNeverStartsVideoOrTouchesAudio() throws Exception {
        field("listenOnly", true);
        service.setScreenInteractive(false);
        service.setVideoOutputVisible(false);
        service.setScreenInteractive(true);
        service.setVideoOutputVisible(true);
        verify(video, never()).prepare();
        verify(video, never()).play();
        verifyNoInteractions(audio, editor);
        assertTrue(ReceiverService.isListenOnly());
        assertEquals(ReceiverService.STATUS_PLAYING, ReceiverService.getCurrentStatus());
    }

    @Test public void screenOnDoesNotResumeAPausedMonitor() throws Exception {
        field("resumeNeedsFreshSession", true);
        field("currentStatus", ReceiverService.STATUS_PAUSED);
        service.setScreenInteractive(false);
        service.setScreenInteractive(true);
        verify(video, never()).prepare();
        verifyNoInteractions(audio);
        assertEquals(ReceiverService.STATUS_PAUSED, ReceiverService.getCurrentStatus());
    }

    @Test public void screenOffKeepsRealAudioOutageAlarmArmed() throws Exception {
        field("hasConnectedInCurrentSession", true);
        Runnable alarm = mock(Runnable.class);
        field("alarmRunnable", alarm);
        when(settings.getInt(AppSettings.KEY_ALARM_DELAY_SECONDS,
                AppSettings.DEFAULT_ALARM_DELAY_SECONDS)).thenReturn(15);
        doCallRealMethod().when(service).onPlayerError(any());
        service.setScreenInteractive(false);
        try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
            clock.when(SystemClock::elapsedRealtime).thenReturn(10_000L);
            service.onPlayerError(mock(PlaybackException.class));
        }
        verify((Handler) read("mainHandler")).postDelayed(alarm, 15_000L);
        assertEquals(10_000L, read("outageStartedAt"));
        assertEquals(ReceiverService.STATUS_RECONNECTING, ReceiverService.getCurrentStatus());
    }

    @Test public void changingVolumeDuringTalkStaysMutedAndRestoresNewVolume() throws Exception {
        field("talking", true);
        service.setPlaybackVolume(37);
        verify(audio).setVolume(0f);
        Method stopTalk = ReceiverService.class.getDeclaredMethod("stopTalkback");
        stopTalk.setAccessible(true);
        stopTalk.invoke(service);
        verify(audio).setVolume(0.37f);
        verify(editor).putInt(AppSettings.KEY_PLAYBACK_VOLUME, 37);
    }

    @Test public void volumeIsClampedToSupportedRange() {
        service.setPlaybackVolume(180);
        verify(audio).setVolume(1f);
        service.setPlaybackVolume(-5);
        verify(audio).setVolume(0f);
    }

    @Test public void rotationNormalizesPersistedValues() {
        assertEquals(0, RotatablePlayerView.normalizeRotation(360));
        assertEquals(270, RotatablePlayerView.normalizeRotation(-90));
        assertEquals(90, RotatablePlayerView.normalizeRotation(450));
        assertEquals(0, RotatablePlayerView.normalizeRotation(45));
    }

    private Object read(String name) throws Exception {
        Field field = ReceiverService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private void field(String name, Object value) throws Exception {
        Field field = ReceiverService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
