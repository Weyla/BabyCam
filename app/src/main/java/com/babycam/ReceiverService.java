package com.babycam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.io.IOException;
import java.util.Locale;

/** Owns receiver playback so audio and connection monitoring survive a locked screen. */
@OptIn(markerClass = UnstableApi.class)
public final class ReceiverService extends MediaSessionService implements Player.Listener {
    static final String ACTION_CONNECT = "com.babycam.action.RECEIVER_CONNECT";
    static final String ACTION_DISCONNECT = "com.babycam.action.RECEIVER_DISCONNECT";
    static final String ACTION_SILENCE_ALARM = "com.babycam.action.SILENCE_ALARM";
    static final String ACTION_REMOTE_CONTROL = "com.babycam.action.REMOTE_CONTROL";
    static final String ACTION_TALK_START = "com.babycam.action.TALK_START";
    static final String ACTION_TALK_STOP = "com.babycam.action.TALK_STOP";
    static final String ACTION_CONTROL_RESULT = "com.babycam.action.CONTROL_RESULT";
    static final String ACTION_STATUS = "com.babycam.action.RECEIVER_STATUS";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_DISPLAY_URL = "display_url";
    static final String EXTRA_HAS_VIDEO = "has_video";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_RESPONSE = "response";
    static final String EXTRA_BATTERY_LEVEL = "battery_level";
    static final String EXTRA_CHARGING = "charging";
    static final String EXTRA_LOW_BATTERY = "low_battery";
    static final String EXTRA_CAMERA_FACING = "camera_facing";
    static final String EXTRA_TORCH = "torch";
    static final String EXTRA_ZOOM = "zoom";
    static final String EXTRA_MAX_ZOOM = "max_zoom";
    static final String EXTRA_RESOLUTION = "resolution";
    static final String EXTRA_TALKING = "talking";
    static final String STATUS_STOPPED = "Stopped";
    static final String STATUS_CONNECTING = "Connecting…";
    static final String STATUS_PLAYING = "Connected";
    static final String STATUS_RECONNECTING = "Reconnecting…";
    static final String STATUS_ERROR = "Could not connect";

    private static final String ALARM_CHANNEL_ID = "babycam_connection_alarm";
    private static final String BATTERY_CHANNEL_ID = "babycam_battery_warning";
    private static final int ALARM_NOTIFICATION_ID = 8556;
    private static final int BATTERY_NOTIFICATION_ID = 8557;
    private static final long RECONNECT_DELAY_MS = 3_000;
    private static final long STATUS_POLL_MS = 15_000;
    private static final long RESOLUTION_STATUS_POLL_MS = 500;
    private static final long RESOLUTION_RECONNECT_SETTLE_MS = 250;
    private static final long RESOLUTION_CHANGE_TIMEOUT_MS = 15_000;
    private static final long BUFFERING_TIMEOUT_MS = 8_000;

    private static volatile String currentStatus = STATUS_STOPPED;
    private static volatile String currentMessage = "";
    private static volatile boolean running;
    private static volatile boolean listenOnly;
    private static volatile boolean hasVideo;
    private static volatile String displayUrl = "";
    private static volatile int batteryLevel = -1;
    private static volatile boolean charging;
    private static volatile boolean lowBattery;
    private static volatile String cameraFacing = "back";
    private static volatile boolean torchEnabled;
    private static volatile float zoomRatio = 1f;
    private static volatile float maxZoomRatio = 1f;
    private static volatile int remoteResolution = AppSettings.DEFAULT_VIDEO_RESOLUTION;
    private static volatile boolean talking;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaSession mediaSession;
    private MediaPlayer alarmPlayer;
    private long outageStartedAt;
    private boolean hasConnectedInCurrentSession;
    private boolean alarmSilenced;
    private boolean reconnectScheduled;
    private String streamUri;
    private String remoteUsername;
    private String remotePassword;
    private boolean lowLatency;
    private Talkback.Client talkbackClient;
    private float playbackVolumeBeforeTalk = 1f;
    private boolean lowBatteryNotified;
    private boolean resettingPlayer;
    private int pendingResolution = -1;
    private long connectionGeneration;
    private AudioFocusRequest alarmFocusRequest;

    private final Runnable alarmRunnable = () -> {
        if (running && hasConnectedInCurrentSession && outageStartedAt != 0
                && !alarmSilenced) {
            SharedPreferences settings = AppSettings.preferences(this);
            if (settings.getBoolean(AppSettings.KEY_ALARM_ENABLED, false)) {
                startAlarm(settings);
            }
        }
    };

    private final Runnable reconnectRunnable = () -> {
        reconnectScheduled = false;
        if (running && streamUri != null) {
            preparePlayer(false);
        }
    };

    private final Runnable statusPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || streamUri == null) return;
            long generation = connectionGeneration;
            Uri target = Uri.parse(streamUri);
            ArmedControl.requestAsync(target.getHost(), target.getPort() + 1,
                    remoteUsername, remotePassword, "STATUS",
                    response -> mainHandler.post(() -> {
                        if (running && generation == connectionGeneration) {
                            handleControlStatus(response);
                        }
                    }));
            mainHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    private final Runnable resolutionTimeoutRunnable = () -> {
        if (!running || pendingResolution < 0) return;
        pendingResolution = -1;
        reportUnavailable("Resolution change timed out; reconnecting…");
        preparePlayer(false);
    };

    private final Runnable resolutionReconnectRunnable = () -> {
        if (!running || streamUri == null) return;
        pendingResolution = -1;
        mainHandler.removeCallbacks(resolutionTimeoutRunnable);
        preparePlayer(false);
    };

    private final Runnable bufferingTimeoutRunnable = () -> {
        if (running && pendingResolution < 0 && player != null
                && player.getPlaybackState() == Player.STATE_BUFFERING) {
            reportUnavailable("Stream stalled; reconnecting…");
            preparePlayer(false);
        }
    };

    static String getCurrentStatus() {
        return currentStatus;
    }

    static String getCurrentMessage() {
        return currentMessage;
    }

    static boolean isRunning() {
        return running;
    }

    static boolean isListenOnly() {
        return listenOnly;
    }

    static boolean hasVideo() {
        return hasVideo;
    }

    static String getDisplayUrl() {
        return displayUrl;
    }

    static int getBatteryLevel() { return batteryLevel; }
    static boolean isCharging() { return charging; }
    static boolean isLowBattery() { return lowBattery; }
    static String getCameraFacing() { return cameraFacing; }
    static boolean isTorchEnabled() { return torchEnabled; }
    static float getZoomRatio() { return zoomRatio; }
    static float getMaxZoomRatio() { return maxZoomRatio; }
    static int getRemoteResolution() { return remoteResolution; }
    static boolean isTalking() { return talking; }

    @Override
    public void onCreate() {
        super.onCreate();
        createAlarmChannel();
        createBatteryChannel();
        lowLatency = AppSettings.preferences(this).getBoolean(
                AppSettings.KEY_LOW_LATENCY, false);
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this);
        if (lowLatency) {
            playerBuilder.setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250, 1_000, 100, 250)
                    .build());
        }
        player = playerBuilder
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(this);
        mediaSession = new MediaSession.Builder(this, player).build();
        addSession(mediaSession);
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SILENCE_ALARM.equals(action)) {
            alarmSilenced = true;
            stopAlarm();
            return START_NOT_STICKY;
        }
        if (ACTION_REMOTE_CONTROL.equals(action)) {
            sendRemoteControl(intent.getStringExtra(EXTRA_COMMAND));
            return START_NOT_STICKY;
        }
        if (ACTION_TALK_START.equals(action)) {
            startTalkback();
            return START_NOT_STICKY;
        }
        if (ACTION_TALK_STOP.equals(action)) {
            stopTalkback();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            connectFromSettings();
        }
        return START_NOT_STICKY;
    }

    private void connectFromSettings() {
        SharedPreferences settings = AppSettings.preferences(this);
        String hostInput = settings.getString(AppSettings.KEY_RECEIVER_HOST, "");
        String password = settings.getString(AppSettings.KEY_RECEIVER_PASSWORD, "");
        String username = settings.getString(AppSettings.KEY_RECEIVER_USERNAME,
                AppSettings.DEFAULT_USERNAME);
        listenOnly = settings.getBoolean(AppSettings.KEY_LISTEN_ONLY, false);
        streamUri = buildStreamUri(hostInput, username, password);
        if (streamUri == null) {
            publishStatus(STATUS_ERROR, "Enter a valid local IP address");
            stopSelf();
            return;
        }
        running = true;
        connectionGeneration++;
        remoteUsername = username;
        remotePassword = password;
        hasVideo = false;
        displayUrl = removeCredentials(streamUri);
        alarmSilenced = false;
        outageStartedAt = 0;
        hasConnectedInCurrentSession = false;
        pendingResolution = -1;
        resetRemoteStatus();
        mainHandler.removeCallbacks(alarmRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(statusPollRunnable);
        mainHandler.removeCallbacks(resolutionReconnectRunnable);
        mainHandler.removeCallbacks(resolutionTimeoutRunnable);
        mainHandler.removeCallbacks(bufferingTimeoutRunnable);
        stopAlarm();
        publishStatus(STATUS_CONNECTING, "Waiting for the local stream");
        preparePlayer(true);
        if (remotePassword != null && !remotePassword.isEmpty()) {
            mainHandler.post(statusPollRunnable);
        }
    }

    private void sendRemoteControl(String command) {
        if (!running || streamUri == null || command == null || command.trim().isEmpty()) return;
        long generation = connectionGeneration;
        Uri target = Uri.parse(streamUri);
        ArmedControl.requestAsync(target.getHost(), target.getPort() + 1,
                remoteUsername, remotePassword, command,
                response -> mainHandler.post(() -> {
                    if (!running || generation != connectionGeneration) return;
                    if (response != null && response.startsWith("OK")
                            && command.toUpperCase(Locale.ROOT).startsWith("RESOLUTION ")) {
                        beginResolutionChange(command);
                    }
                    sendBroadcast(new Intent(ACTION_CONTROL_RESULT)
                            .setPackage(getPackageName())
                            .putExtra(EXTRA_COMMAND, command)
                            .putExtra(EXTRA_RESPONSE, response));
                    mainHandler.removeCallbacks(statusPollRunnable);
                    mainHandler.postDelayed(statusPollRunnable, 400);
                }));
    }

    private void handleControlStatus(String response) {
        if (!running || response == null || !response.startsWith("OK ")) return;
        boolean remoteStreamRunning = false;
        String[] fields = response.substring(3).split(" ");
        for (String field : fields) {
            int separator = field.indexOf('=');
            if (separator <= 0) continue;
            String key = field.substring(0, separator);
            String value = field.substring(separator + 1);
            try {
                switch (key) {
                    case "battery": batteryLevel = Integer.parseInt(value); break;
                    case "charging": charging = "1".equals(value); break;
                    case "low": lowBattery = "1".equals(value); break;
                    case "facing": cameraFacing = value; break;
                    case "torch": torchEnabled = "1".equals(value); break;
                    case "zoom": zoomRatio = Float.parseFloat(value); break;
                    case "maxZoom": maxZoomRatio = Float.parseFloat(value); break;
                    case "resolution": remoteResolution = Integer.parseInt(value); break;
                    case "running": remoteStreamRunning = "1".equals(value); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        showOrHideBatteryWarning();
        publishStatus(currentStatus, currentMessage);
        if (pendingResolution >= 0) {
            mainHandler.removeCallbacks(statusPollRunnable);
            if (remoteStreamRunning && remoteResolution == pendingResolution) {
                mainHandler.removeCallbacks(resolutionReconnectRunnable);
                mainHandler.postDelayed(resolutionReconnectRunnable,
                        RESOLUTION_RECONNECT_SETTLE_MS);
            } else {
                mainHandler.postDelayed(statusPollRunnable, RESOLUTION_STATUS_POLL_MS);
            }
        }
    }

    private void beginResolutionChange(String command) {
        try {
            int resolution = Integer.parseInt(command.substring(command.indexOf(' ') + 1).trim());
            if (resolution != 480 && resolution != 720 && resolution != 1080) return;
            pendingResolution = resolution;
        } catch (NumberFormatException error) {
            return;
        }
        reconnectScheduled = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(resolutionReconnectRunnable);
        mainHandler.removeCallbacks(resolutionTimeoutRunnable);
        mainHandler.postDelayed(resolutionTimeoutRunnable, RESOLUTION_CHANGE_TIMEOUT_MS);
        if (player != null) {
            resettingPlayer = true;
            try {
                player.stop();
                player.clearMediaItems();
            } finally {
                resettingPlayer = false;
            }
        }
        if (hasVideo) {
            hasVideo = false;
            publishStatus(currentStatus, currentMessage);
        }
        if (hasConnectedInCurrentSession) {
            beginOutage(STATUS_RECONNECTING, "Changing video resolution…");
        } else {
            publishStatus(STATUS_CONNECTING, "Waiting for the updated stream");
        }
    }

    private void startTalkback() {
        if (!running || streamUri == null || talking || remotePassword == null
                || remotePassword.isEmpty()) return;
        long generation = connectionGeneration;
        Uri target = Uri.parse(streamUri);
        playbackVolumeBeforeTalk = player.getVolume();
        player.setVolume(0f);
        talking = true;
        publishStatus(currentStatus, currentMessage);
        talkbackClient = new Talkback.Client(this, target.getHost(), target.getPort() + 2,
                remoteUsername, remotePassword, new Talkback.Listener() {
                    @Override public void onConnected() { }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            if (!running || generation != connectionGeneration) return;
                            stopTalkback();
                            sendBroadcast(new Intent(ACTION_CONTROL_RESULT)
                                    .setPackage(getPackageName())
                                    .putExtra(EXTRA_COMMAND, "TALK")
                                    .putExtra(EXTRA_RESPONSE, "ERROR " + message));
                        });
                    }
                });
        talkbackClient.start();
    }

    private void stopTalkback() {
        Talkback.Client client = talkbackClient;
        talkbackClient = null;
        talking = false;
        if (player != null) player.setVolume(playbackVolumeBeforeTalk);
        if (client != null) client.close();
        if (running) publishStatus(currentStatus, currentMessage);
    }

    private void preparePlayer(boolean initial) {
        if (player == null || streamUri == null) {
            return;
        }
        resettingPlayer = true;
        try {
            player.stop();
            player.clearMediaItems();
        } finally {
            resettingPlayer = false;
        }
        if (hasVideo) {
            hasVideo = false;
            publishStatus(currentStatus, currentMessage);
        }
        Uri target = Uri.parse(streamUri);
        ArmedControl.requestStartAsync(target.getHost(), target.getPort() + 1,
                remoteUsername, remotePassword, !listenOnly);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listenOnly)
                .build());
        MediaItem item = new MediaItem.Builder()
                .setUri(streamUri)
                .setMediaId("babycam-live")
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("BabyCam receiver")
                        .setArtist("Local live stream")
                        .build())
                .build();
        RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(!lowLatency)
                .createMediaSource(item);
        player.setMediaSource(mediaSource);
        player.setPlayWhenReady(true);
        player.prepare();
        onUpdateNotificationAsync(mediaSession, true);
        if (!initial) {
            publishStatus(hasConnectedInCurrentSession ? STATUS_RECONNECTING : STATUS_CONNECTING,
                    hasConnectedInCurrentSession ? "Trying to reconnect…"
                            : "Waiting for the local stream");
        }
    }

    private static String buildStreamUri(String input, String username, String password) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String candidate = input.trim();
        if (!candidate.contains("://")) {
            candidate = "rtsp://" + candidate;
        }
        Uri parsed = Uri.parse(candidate);
        String host = parsed.getHost();
        if (!RtspServer.isPrivateIpv4Literal(host)) {
            return null;
        }
        int port = parsed.getPort() > 0 ? parsed.getPort() : RtspServer.DEFAULT_PORT;
        if (port > 65_533) return null;
        String credentials = password == null || password.isEmpty()
                ? "" : Uri.encode(username) + ":" + Uri.encode(password) + "@";
        return "rtsp://" + credentials + host + ":" + port + "/live";
    }

    private static String removeCredentials(String uri) {
        Uri parsed = Uri.parse(uri);
        return "rtsp://" + parsed.getHost() + ":" + parsed.getPort() + "/live";
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (tracks.getGroups().isEmpty()) {
            if (hasVideo) {
                hasVideo = false;
                publishStatus(currentStatus, currentMessage);
            }
            return;
        }
        boolean videoTrackPresent = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                videoTrackPresent = true;
                break;
            }
        }
        boolean updated = !listenOnly && videoTrackPresent;
        if (hasVideo != updated) {
            hasVideo = updated;
            publishStatus(currentStatus, currentMessage);
        }
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (!running) {
            return;
        }
        if (playbackState == Player.STATE_READY) {
            mainHandler.removeCallbacks(bufferingTimeoutRunnable);
            if (!player.getPlayWhenReady()) {
                player.setPlayWhenReady(true);
            }
            recoverConnection();
        } else if (playbackState == Player.STATE_BUFFERING) {
            reportUnavailable("Stream interrupted; reconnecting…");
            mainHandler.removeCallbacks(bufferingTimeoutRunnable);
            mainHandler.postDelayed(bufferingTimeoutRunnable, BUFFERING_TIMEOUT_MS);
        } else if (!resettingPlayer && pendingResolution < 0
                && (playbackState == Player.STATE_ENDED
                || playbackState == Player.STATE_IDLE)) {
            mainHandler.removeCallbacks(bufferingTimeoutRunnable);
            reportUnavailable("Stream interrupted; reconnecting…");
            scheduleReconnect();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (!running) {
            return;
        }
        mainHandler.removeCallbacks(bufferingTimeoutRunnable);
        reportUnavailable(friendlyPlaybackError(error));
        scheduleReconnect();
    }

    private void reportUnavailable(String message) {
        if (hasConnectedInCurrentSession) {
            beginOutage(STATUS_RECONNECTING, message);
        } else {
            publishStatus(STATUS_CONNECTING, "Waiting for the local stream");
        }
    }

    private void beginOutage(String status, String message) {
        if (outageStartedAt == 0) {
            outageStartedAt = SystemClock.elapsedRealtime();
            int delaySeconds = AppSettings.preferences(this).getInt(
                    AppSettings.KEY_ALARM_DELAY_SECONDS,
                    AppSettings.DEFAULT_ALARM_DELAY_SECONDS);
            mainHandler.removeCallbacks(alarmRunnable);
            mainHandler.postDelayed(alarmRunnable, Math.max(1, delaySeconds) * 1_000L);
        }
        publishStatus(status, message);
    }

    private void recoverConnection() {
        hasConnectedInCurrentSession = true;
        outageStartedAt = 0;
        alarmSilenced = false;
        reconnectScheduled = false;
        pendingResolution = -1;
        mainHandler.removeCallbacks(alarmRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(resolutionReconnectRunnable);
        mainHandler.removeCallbacks(resolutionTimeoutRunnable);
        mainHandler.removeCallbacks(bufferingTimeoutRunnable);
        stopAlarm();
        publishStatus(STATUS_PLAYING,
                listenOnly ? "Playing audio in the background" : "Playing video and audio");
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled) {
            reconnectScheduled = true;
            mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
        }
    }

    private static String friendlyPlaybackError(PlaybackException error) {
        Throwable cause = error.getCause();
        String detail = cause == null ? error.getMessage() : cause.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "The stream is unavailable; retrying…";
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthorized")) {
            return "Incorrect stream password; retrying…";
        }
        return "The stream is unavailable; retrying…";
    }

    private void startAlarm(SharedPreferences settings) {
        stopAlarm();
        String savedSound = settings.getString(AppSettings.KEY_ALARM_SOUND_URI, "");
        Uri sound = savedSound == null || savedSound.isEmpty()
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse(savedSound);
        if (sound != null) {
            try {
                AudioManager audioManager = getSystemService(AudioManager.class);
                alarmFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
                if (audioManager != null) audioManager.requestAudioFocus(alarmFocusRequest);
                alarmPlayer = new MediaPlayer();
                alarmPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                alarmPlayer.setDataSource(this, sound);
                alarmPlayer.setLooping(true);
                float volume = Math.max(0, Math.min(100, settings.getInt(
                        AppSettings.KEY_ALARM_VOLUME,
                        AppSettings.DEFAULT_ALARM_VOLUME))) / 100f;
                alarmPlayer.setVolume(volume, volume);
                alarmPlayer.prepare();
                alarmPlayer.start();
            } catch (IOException | RuntimeException error) {
                stopAlarm();
            }
        }
        showAlarmNotification();
    }

    private void showAlarmNotification() {
        Intent silenceIntent = new Intent(this, ReceiverService.class)
                .setAction(ACTION_SILENCE_ALARM);
        PendingIntent silence = PendingIntent.getService(this, 1, silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_babycam)
                .setContentTitle("BabyCam connection lost")
                .setContentText("The receiver has not recovered the local stream")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Silence alarm", silence).build())
                .build();
        getSystemService(NotificationManager.class).notify(ALARM_NOTIFICATION_ID, notification);
    }

    private void stopAlarm() {
        if (alarmPlayer != null) {
            try {
                alarmPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            alarmPlayer.release();
            alarmPlayer = null;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(ALARM_NOTIFICATION_ID);
        }
        AudioManager audioManager = getSystemService(AudioManager.class);
        if (audioManager != null && alarmFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(alarmFocusRequest);
        }
        alarmFocusRequest = null;
    }

    private void publishStatus(String status, String message) {
        currentStatus = status;
        currentMessage = message == null ? "" : message;
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, currentStatus)
                .putExtra(EXTRA_MESSAGE, currentMessage)
                .putExtra(EXTRA_DISPLAY_URL, displayUrl)
                .putExtra(EXTRA_HAS_VIDEO, hasVideo)
                .putExtra(EXTRA_BATTERY_LEVEL, batteryLevel)
                .putExtra(EXTRA_CHARGING, charging)
                .putExtra(EXTRA_LOW_BATTERY, lowBattery)
                .putExtra(EXTRA_CAMERA_FACING, cameraFacing)
                .putExtra(EXTRA_TORCH, torchEnabled)
                .putExtra(EXTRA_ZOOM, zoomRatio)
                .putExtra(EXTRA_MAX_ZOOM, maxZoomRatio)
                .putExtra(EXTRA_RESOLUTION, remoteResolution)
                .putExtra(EXTRA_TALKING, talking));
    }

    private void disconnect() {
        running = false;
        connectionGeneration++;
        hasVideo = false;
        hasConnectedInCurrentSession = false;
        outageStartedAt = 0;
        reconnectScheduled = false;
        pendingResolution = -1;
        mainHandler.removeCallbacks(alarmRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(statusPollRunnable);
        mainHandler.removeCallbacks(resolutionReconnectRunnable);
        mainHandler.removeCallbacks(resolutionTimeoutRunnable);
        mainHandler.removeCallbacks(bufferingTimeoutRunnable);
        stopTalkback();
        stopAlarm();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        resetRemoteStatus();
        publishStatus(STATUS_STOPPED, "");
    }

    private void createAlarmChannel() {
        NotificationChannel channel = new NotificationChannel(ALARM_CHANNEL_ID,
                "Connection loss alarm", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Alerts when a BabyCam stream remains unavailable");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void createBatteryChannel() {
        NotificationChannel channel = new NotificationChannel(BATTERY_CHANNEL_ID,
                "Streamer battery warnings", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Warns when the streaming phone battery is low");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void showOrHideBatteryWarning() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (!lowBattery || batteryLevel < 0) {
            manager.cancel(BATTERY_NOTIFICATION_ID);
            lowBatteryNotified = false;
            return;
        }
        if (lowBatteryNotified) return;
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(BATTERY_NOTIFICATION_ID, new Notification.Builder(this, BATTERY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_babycam)
                .setContentTitle("Streaming phone battery is low")
                .setContentText("Battery is at " + batteryLevel + "%")
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build());
        lowBatteryNotified = true;
    }

    private void resetRemoteStatus() {
        batteryLevel = -1;
        charging = false;
        lowBattery = false;
        cameraFacing = "back";
        torchEnabled = false;
        zoomRatio = 1f;
        maxZoomRatio = 1f;
        remoteResolution = AppSettings.DEFAULT_VIDEO_RESOLUTION;
        talking = false;
        lowBatteryNotified = false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(BATTERY_NOTIFICATION_ID);
    }

    @Override
    public void onDestroy() {
        disconnect();
        if (mediaSession != null) {
            if (isSessionAdded(mediaSession)) removeSession(mediaSession);
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
