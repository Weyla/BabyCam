package com.babycam;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
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
        field("hasVideo", true);
        field("listenOnly", false);
        field("talking", false);
        field("currentStatus", ReceiverService.STATUS_PLAYING);
        field("streamUri", "rtsp://192.168.1.10:8554/live");
        SharedPreferences settings = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(service.getSharedPreferences(eq(AppSettings.PREFS), anyInt())).thenReturn(settings);
        when(settings.edit()).thenReturn(editor);
        doCallRealMethod().when(service).setListenOnly(anyBoolean());
        doCallRealMethod().when(service).setPlaybackVolume(anyInt());
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

    private void field(String name, Object value) throws Exception {
        Field field = ReceiverService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
