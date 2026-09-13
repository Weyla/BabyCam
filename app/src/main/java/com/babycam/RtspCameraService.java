package com.babycam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class RtspCameraService extends Service
        implements CameraController.Listener, RtspServer.Listener {
    private static final String TAG = "BabyCam";
    static final String ACTION_START = "com.babycam.action.START";
    static final String ACTION_STOP = "com.babycam.action.STOP";
    static final String ACTION_ARM = "com.babycam.action.ARM";
    static final String ACTION_DISARM = "com.babycam.action.DISARM";
    static final String ACTION_STATUS = "com.babycam.action.STATUS";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_VIDEO_ENABLED = "video_enabled";
    static final String EXTRA_VIDEO_RESOLUTION = "video_resolution";
    static final String STATUS_STOPPED = "Stopped";
    static final String STATUS_STARTING = "Starting camera…";
    static final String STATUS_STREAMING = "Streaming";
    static final String STATUS_STANDBY = "Standby";
    static final String STATUS_CONNECTED = "Connected";
    static final String STATUS_ERROR = "Error";
    private static final String CHANNEL_ID = "babycam_stream";
    private static final int NOTIFICATION_ID = 8554;
    private static final long STANDBY_VIEWER_TIMEOUT_MS = 20_000;

    private static volatile String currentStatus = STATUS_STOPPED;
    private static volatile String currentMessage = "";
    private static volatile boolean running;
    private static volatile boolean currentVideoEnabled = true;
    private static volatile int currentPort = AppSettings.DEFAULT_STREAM_PORT;
    private static volatile int currentVideoResolution = AppSettings.DEFAULT_VIDEO_RESOLUTION;
    private static volatile boolean armed;

    private volatile RtspServer server;
    private H264Encoder videoEncoder;
    private AacEncoder audioEncoder;
    private volatile CameraController cameraController;
    private PowerManager.WakeLock wakeLock;
    private volatile Thread startThread;
    private ArmedControl.Server controlServer;
    private Talkback.Server talkbackServer;
    private LocalDeviceDiscovery.Advertiser advertiser;
    private volatile boolean standbyStartedStream;
    private volatile boolean standbyViewerConnected;
    private volatile boolean standbyViewerLeftWhileStarting;
    private final AtomicLong startupGeneration = new AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable standbyViewerTimeout = () -> {
        if (running && standbyStartedStream && !standbyViewerConnected
                && startThread == null) {
            stopStreaming();
            if (isArmedEnabled()) {
                enterArmedMode("No viewer connected • Back in standby");
            } else {
                stopSelf();
            }
        }
    };

    static boolean isRunning() {
        return running;
    }

    static String getCurrentStatus() {
        return currentStatus;
    }

    static String getCurrentMessage() {
        return currentMessage;
    }

    static boolean isVideoEnabled() {
        return currentVideoEnabled;
    }

    static int getCurrentPort() {
        return currentPort;
    }

    static boolean isArmed() {
        return armed;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":stream");
        wakeLock.setReferenceCounted(false);
        advertiser = new LocalDeviceDiscovery.Advertiser(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (isArmedEnabled()) {
                enterArmedMode("");
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_DISARM.equals(action)) {
            if (!running) cancelPendingStartup();
            standbyStartedStream = false;
            standbyViewerConnected = false;
            standbyViewerLeftWhileStarting = false;
            stopArmedControl();
            if (running) {
                ensureControlServer();
                updateAdvertisement();
                publishStatus(STATUS_STREAMING,
                        currentVideoEnabled ? "Video and audio are live" : "Audio is live");
            }
            if (!running) {
                stopStreaming();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_ARM.equals(action)) {
            stopArmedControl();
            if (running) {
                ensureControlServer();
                updateAdvertisement();
                if (standbyStartedStream && standbyViewerConnected) {
                    publishStandbyConnectedStatus();
                } else {
                    publishStatus(STATUS_STREAMING,
                            currentVideoEnabled ? "Video and audio are live" : "Audio is live");
                }
            } else {
                enterArmedMode("");
            }
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            cancelPendingStartup();
            stopStreaming();
            if (isArmedEnabled()) {
                enterArmedMode("");
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running && startThread == null) {
            stopArmedControl();
            standbyStartedStream = false;
            standbyViewerConnected = false;
            standbyViewerLeftWhileStarting = false;
            currentVideoEnabled = intent == null
                    || intent.getBooleanExtra(EXTRA_VIDEO_ENABLED, true);
            currentVideoResolution = AppSettings.normalizeVideoResolution(
                    intent.getIntExtra(EXTRA_VIDEO_RESOLUTION,
                            AppSettings.DEFAULT_VIDEO_RESOLUTION));
            startForegroundNow(currentVideoEnabled);
            publishStatus(STATUS_STARTING,
                    currentVideoEnabled ? "Preparing camera and microphone" : "Preparing microphone");
            boolean startVideo = currentVideoEnabled;
            AppSettings.preferences(this).edit()
                    .putBoolean(AppSettings.KEY_LAST_VIDEO_MODE, startVideo)
                    .putInt(AppSettings.KEY_VIDEO_RESOLUTION, currentVideoResolution).apply();
            int startResolution = currentVideoResolution;
            startStreamingThread(startVideo, startResolution, "BabyCam-service-start");
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("InlinedApi")
    private void startArmedForeground() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_babycam)
                .setContentTitle("BabyCam standby")
                .setContentText((currentVideoEnabled ? "Camera and microphone are off"
                        : "Microphone is off") + " • Control port " + (currentPort + 1))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (currentVideoEnabled) {
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void enterArmedMode(String message) {
        currentVideoEnabled = AppSettings.preferences(this).getBoolean(
                AppSettings.KEY_LAST_VIDEO_MODE, true);
        currentVideoResolution = AppSettings.normalizeVideoResolution(
                AppSettings.preferences(this).getInt(AppSettings.KEY_VIDEO_RESOLUTION,
                        AppSettings.DEFAULT_VIDEO_RESOLUTION));
        if (!hasCapturePermissions(currentVideoEnabled)) {
            disableStandby();
            publishStatus(STATUS_ERROR, currentVideoEnabled
                    ? "Camera and microphone permissions are required for standby"
                    : "Microphone permission is required for standby");
            stopSelf();
            return;
        }
        String password = AppSettings.preferences(this).getString(
                AppSettings.KEY_STREAM_PASSWORD, "");
        if (password == null || password.isEmpty()) {
            disableStandby();
            publishStatus(STATUS_ERROR, "Set a stream password before starting standby");
            stopSelf();
            return;
        }
        currentPort = AppSettings.normalizeStreamPort(AppSettings.preferences(this).getInt(
                AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT));
        if ("127.0.0.1".equals(RtspServer.getLocalIpv4Address())) {
            publishStatus(STATUS_ERROR, "Connect this phone to a local network for standby");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        startArmedForeground();
        if (ensureControlServer()) {
            updateAdvertisement();
            publishStatus(STATUS_STANDBY, message == null || message.isEmpty()
                    ? (currentVideoEnabled ? "Waiting for a viewer • Video + audio • "
                    + currentVideoResolution + "p" : "Waiting for a viewer • Audio only")
                    : message);
        }
    }

    @SuppressLint("WakelockTimeout") // Released when the foreground service stops.
    private boolean ensureControlServer() {
        // The CPU must service incoming LAN connections even with the screen off.
        if (isArmedEnabled() && !wakeLock.isHeld()) wakeLock.acquire();
        if (controlServer != null) return true;
        String username = AppSettings.preferences(this).getString(
                AppSettings.KEY_STREAM_USERNAME, AppSettings.DEFAULT_USERNAME);
        String password = AppSettings.preferences(this).getString(
                AppSettings.KEY_STREAM_PASSWORD, "");
        if (password == null || password.isEmpty()) {
            armed = false;
            return false;
        }
        currentPort = AppSettings.normalizeStreamPort(AppSettings.preferences(this).getInt(
                AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT));
        try {
            controlServer = new ArmedControl.Server(currentPort + 1, username, password,
                    this::handleControlCommand);
            controlServer.start();
            armed = true;
            return true;
        } catch (IOException error) {
            Log.e(TAG, "Could not arm remote start", error);
            stopArmedControl();
            disableStandby();
            publishStatus(STATUS_ERROR, "Could not open the standby control port");
            if (!running) stopSelf();
            return false;
        }
    }

    private void startFromRemoteRequest(boolean videoRequested) {
        if (running || startThread != null || !isArmedEnabled()) return;
        standbyStartedStream = true;
        standbyViewerConnected = false;
        standbyViewerLeftWhileStarting = false;
        currentVideoEnabled = videoRequested && AppSettings.preferences(this).getBoolean(
                AppSettings.KEY_LAST_VIDEO_MODE, true);
        currentVideoResolution = AppSettings.normalizeVideoResolution(
                AppSettings.preferences(this).getInt(AppSettings.KEY_VIDEO_RESOLUTION,
                        AppSettings.DEFAULT_VIDEO_RESOLUTION));
        startForegroundNow(currentVideoEnabled);
        publishStatus(STATUS_STARTING, "Remote start request received");
        boolean startVideo = currentVideoEnabled;
        int startResolution = currentVideoResolution;
        startStreamingThread(startVideo, startResolution, "BabyCam-remote-stream-start");
    }

    private boolean hasCapturePermissions(boolean videoEnabled) {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && (!videoEnabled || checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED);
    }

    private boolean isArmedEnabled() {
        return AppSettings.preferences(this).getBoolean(AppSettings.KEY_ARMED_ENABLED, false);
    }

    private void disableStandby() {
        standbyStartedStream = false;
        standbyViewerConnected = false;
        standbyViewerLeftWhileStarting = false;
        AppSettings.preferences(this).edit()
                .putBoolean(AppSettings.KEY_ARMED_ENABLED, false).apply();
    }

    private void stopArmedControl() {
        armed = false;
        if (controlServer != null) {
            controlServer.close();
            controlServer = null;
        }
    }

    @SuppressLint("InlinedApi") // Foreground service-type constants are safe in guarded calls.
    private void startForegroundNow(boolean videoEnabled) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_babycam)
                .setContentTitle(videoEnabled ? "Camera stream is starting" : "Audio stream is starting")
                .setContentText("Available on this local network")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (videoEnabled) {
                types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressLint("WakelockTimeout") // Held only while the visible foreground stream is active.
    private synchronized void startStreaming(boolean videoEnabled, int videoResolution,
                                             long generation) {
        try {
            ensureStartupActive(generation);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission is required");
            }
            if (videoEnabled && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Camera permission is required for video streaming");
            }
            String password = AppSettings.preferences(this)
                    .getString(AppSettings.KEY_STREAM_PASSWORD, "");
            String username = AppSettings.preferences(this).getString(
                    AppSettings.KEY_STREAM_USERNAME, AppSettings.DEFAULT_USERNAME);
            currentPort = AppSettings.normalizeStreamPort(AppSettings.preferences(this).getInt(
                    AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT));
            if ("127.0.0.1".equals(RtspServer.getLocalIpv4Address())) {
                throw new IOException("Connect this phone to a local network first");
            }
            server = new RtspServer(videoEnabled, username, password, currentPort, this);
            server.start();
            ensureStartupActive(generation);
            if (password != null && !password.isEmpty()) {
                try {
                    talkbackServer = new Talkback.Server(currentPort + 2, username, password);
                    talkbackServer.start();
                } catch (IOException talkbackError) {
                    talkbackServer = null;
                    Log.w(TAG, "Talkback port is unavailable", talkbackError);
                }
            }
            audioEncoder = new AacEncoder(server);
            audioEncoder.start();
            ensureStartupActive(generation);
            if (videoEnabled) {
                boolean lowLatency = AppSettings.preferences(this).getBoolean(
                        AppSettings.KEY_LOW_LATENCY, false);
                videoEncoder = new H264Encoder(server, videoResolution, lowLatency);
                videoEncoder.start();
                cameraController = new CameraController(this, videoEncoder.getInputSurface(), this);
                cameraController.start();
                ensureStartupActive(generation);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
            running = true;
            ensureControlServer();
            updateAdvertisement();
            if (standbyStartedStream && standbyViewerLeftWhileStarting) {
                mainHandler.post(() -> handlePlayingClientCountChanged(0));
            } else if (standbyStartedStream && standbyViewerConnected) {
                publishStandbyConnectedStatus();
            } else {
                publishStatus(STATUS_STREAMING,
                        videoEnabled ? "Video and audio are live • " + videoResolution + "p"
                                : "Audio is live");
            }
            if (standbyStartedStream && !standbyViewerConnected) {
                mainHandler.removeCallbacks(standbyViewerTimeout);
                mainHandler.postDelayed(standbyViewerTimeout, STANDBY_VIEWER_TIMEOUT_MS);
            }
        } catch (Throwable error) {
            boolean cancelled = error instanceof StartupCancelledException
                    || generation != startupGeneration.get();
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            if (!cancelled) Log.e(TAG, "Could not start stream: " + message, error);
            stopStreaming();
            if (cancelled) {
                return;
            } else if (isArmedEnabled()) {
                enterArmedMode("Stream stopped: " + message);
            } else {
                publishStatus(STATUS_ERROR, message);
                stopSelf();
            }
        } finally {
            if (startThread == Thread.currentThread()) startThread = null;
        }
    }

    private void startStreamingThread(boolean videoEnabled, int resolution, String threadName) {
        long generation = startupGeneration.incrementAndGet();
        startThread = new Thread(() -> startStreaming(videoEnabled, resolution, generation),
                threadName);
        startThread.start();
    }

    private void cancelPendingStartup() {
        startupGeneration.incrementAndGet();
        Thread pending = startThread;
        if (pending != null && pending != Thread.currentThread()) pending.interrupt();
    }

    private void ensureStartupActive(long generation) throws StartupCancelledException {
        if (generation != startupGeneration.get() || Thread.currentThread().isInterrupted()) {
            throw new StartupCancelledException();
        }
    }

    private synchronized void stopStreaming() {
        running = false;
        mainHandler.removeCallbacks(standbyViewerTimeout);
        standbyStartedStream = false;
        standbyViewerConnected = false;
        standbyViewerLeftWhileStarting = false;
        if (talkbackServer != null) {
            talkbackServer.close();
            talkbackServer = null;
        }
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder = null;
        }
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (!isArmedEnabled() && wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        publishStatus(STATUS_STOPPED, "");
    }

    private String handleControlCommand(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        String command = parts[0].toUpperCase(Locale.ROOT);
        if ("START".equals(command) || "START_VIDEO".equals(command)
                || "START_AUDIO".equals(command)) {
            boolean video = !"START_AUDIO".equals(command);
            mainHandler.post(() -> startFromRemoteRequest(video));
            return "OK";
        }
        if ("STATUS".equals(command)) return buildControlStatus();
        if (!running || !currentVideoEnabled || cameraController == null) {
            return "ERROR video_unavailable";
        }
        switch (command) {
            case "TORCH":
                if (parts.length != 2) return "ERROR invalid_value";
                boolean torch = "ON".equalsIgnoreCase(parts[1]);
                if (!torch && !"OFF".equalsIgnoreCase(parts[1])) {
                    return "ERROR invalid_value";
                }
                if (torch && !cameraController.hasTorch()) return "ERROR torch_unavailable";
                cameraController.setTorch(torch);
                return "OK";
            case "CAMERA":
                if (parts.length != 2 || !"SWITCH".equalsIgnoreCase(parts[1])) {
                    return "ERROR invalid_value";
                }
                if (!cameraController.hasFrontCamera() || !cameraController.hasBackCamera()) {
                    return "ERROR camera_unavailable";
                }
                cameraController.switchCamera();
                return "OK";
            case "ZOOM":
                if (parts.length != 2) return "ERROR invalid_value";
                try {
                    float zoom = Float.parseFloat(parts[1]);
                    if (!Float.isFinite(zoom)) return "ERROR invalid_value";
                    cameraController.setZoomRatio(zoom);
                    return "OK";
                } catch (NumberFormatException error) {
                    return "ERROR invalid_value";
                }
            case "RESOLUTION":
                if (parts.length != 2) return "ERROR invalid_value";
                try {
                    int requested = Integer.parseInt(parts[1]);
                    if (requested != 480 && requested != 720 && requested != 1080) {
                        return "ERROR invalid_value";
                    }
                    mainHandler.post(() -> restartVideoAtResolution(requested));
                    return "OK";
                } catch (NumberFormatException error) {
                    return "ERROR invalid_value";
                }
            default:
                return "ERROR unknown_command";
        }
    }

    private String buildControlStatus() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        boolean charging = false;
        if (battery != null) {
            int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (raw >= 0 && scale > 0) level = Math.round(raw * 100f / scale);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        CameraController camera = cameraController;
        String facing = camera != null
                && camera.getLensFacing() == CameraCharacteristics.LENS_FACING_FRONT
                ? "front" : "back";
        boolean torch = camera != null && camera.isTorchEnabled();
        float zoom = camera == null ? 1f : camera.getZoomRatio();
        float maxZoom = camera == null ? 1f : camera.getMaxZoomRatio();
        return String.format(Locale.US,
                "OK battery=%d charging=%d low=%d running=%d video=%d facing=%s torch=%d zoom=%.2f maxZoom=%.2f resolution=%d",
                level, charging ? 1 : 0, level >= 0 && level <= 20 && !charging ? 1 : 0,
                running ? 1 : 0, currentVideoEnabled ? 1 : 0, facing, torch ? 1 : 0,
                zoom, maxZoom, currentVideoResolution);
    }

    private void restartVideoAtResolution(int resolution) {
        if (!running || !currentVideoEnabled || startThread != null
                || resolution == currentVideoResolution) return;
        boolean returnToStandby = standbyStartedStream;
        AppSettings.preferences(this).edit()
                .putInt(AppSettings.KEY_VIDEO_RESOLUTION, resolution).apply();
        stopStreaming();
        standbyStartedStream = returnToStandby;
        standbyViewerConnected = false;
        standbyViewerLeftWhileStarting = false;
        currentVideoEnabled = true;
        currentVideoResolution = resolution;
        startForegroundNow(true);
        publishStatus(STATUS_STARTING, "Applying remote resolution • " + resolution + "p");
        startStreamingThread(true, resolution, "BabyCam-resolution-restart");
    }

    private void updateAdvertisement() {
        if (advertiser == null || (!running && !isArmedEnabled())) return;
        String username = AppSettings.preferences(this).getString(
                AppSettings.KEY_STREAM_USERNAME, AppSettings.DEFAULT_USERNAME);
        advertiser.start("BabyCam " + Build.MODEL, currentPort, username, currentVideoEnabled);
    }

    @Override
    public void onCameraError(CameraController source, String message, Throwable error) {
        if ((running || startThread != null) && source == cameraController) {
            cancelPendingStartup();
            Log.e(TAG, message, error);
            stopStreaming();
            if (isArmedEnabled()) enterArmedMode("Stream stopped: " + message);
            else {
                publishStatus(STATUS_ERROR, message);
                stopSelf();
            }
        }
    }

    @Override
    public void onStreamError(RtspServer source, String message, Throwable error) {
        if ((running || startThread != null) && source == server) {
            cancelPendingStartup();
            Log.e(TAG, message, error);
            stopStreaming();
            if (isArmedEnabled()) enterArmedMode("Stream stopped: " + message);
            else {
                publishStatus(STATUS_ERROR, message);
                stopSelf();
            }
        }
    }

    @Override
    public void onPlayingClientCountChanged(RtspServer source, int clientCount) {
        mainHandler.post(() -> {
            // A resolution change replaces the whole RTSP server. Disconnect callbacks
            // from the retired server must not send a freshly restarted standby stream
            // back to standby.
            if (source == server) {
                handlePlayingClientCountChanged(source.getPlayingClientCount());
            }
        });
    }

    private void handlePlayingClientCountChanged(int clientCount) {
        if (!standbyStartedStream) return;
        if (clientCount > 0) {
            mainHandler.removeCallbacks(standbyViewerTimeout);
            if (!standbyViewerConnected) {
                standbyViewerConnected = true;
                if (running) publishStandbyConnectedStatus();
            }
        } else if (standbyViewerConnected) {
            if (!running) {
                standbyViewerLeftWhileStarting = true;
                return;
            }
            stopStreaming();
            if (isArmedEnabled()) {
                enterArmedMode("Viewer disconnected • Back in standby");
            } else {
                stopSelf();
            }
        }
    }

    private void publishStandbyConnectedStatus() {
        publishStatus(STATUS_CONNECTED, currentVideoEnabled
                ? "Viewer connected • Video + audio"
                : "Viewer connected • Audio only");
    }

    private void publishStatus(String status, String message) {
        currentStatus = status;
        currentMessage = message == null ? "" : message;
        Intent statusIntent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, currentStatus)
                .putExtra(EXTRA_MESSAGE, currentMessage)
                .putExtra(EXTRA_VIDEO_ENABLED, currentVideoEnabled);
        sendBroadcast(statusIntent);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null && (STATUS_STREAMING.equals(status)
                || STATUS_CONNECTED.equals(status))) {
            notifications.notify(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_babycam)
                    .setContentTitle(currentVideoEnabled
                            ? "Video and audio are live" : "Audio is live")
                    .setContentText("rtsp://" + RtspServer.getLocalIpv4Address() + ":"
                            + currentPort + "/live")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build());
        }
    }

    private void createNotificationChannel() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "BabyCam streaming",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the camera and microphone RTSP stream running");
        notifications.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        cancelPendingStartup();
        stopArmedControl();
        if (advertiser != null) {
            advertiser.close();
            advertiser = null;
        }
        stopStreaming();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class StartupCancelledException extends Exception {
    }
}
