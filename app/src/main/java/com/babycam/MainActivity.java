package com.babycam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 100;
    private static final int ALARM_SOUND_REQUEST = 101;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_VIDEO_STREAM = 1;
    private static final int PENDING_AUDIO_STREAM = 2;
    private static final int PENDING_RECEIVER = 3;
    private static final int PENDING_ARM = 4;
    private static final int PENDING_TALK = 5;
    private static final String KEY_UI_MODE = "ui_mode";
    private static final String MODE_STREAM = "stream";
    private static final String MODE_RECEIVE = "receive";
    private static final int[] ALARM_DELAYS = {5, 10, 15, 30, 60, 120};
    private static final int[] VIDEO_RESOLUTIONS = {480, 720, 1080};

    private View streamerPanel;
    private View receiverPanel;
    private View receiverSetupCard;
    private View receiverConnectionCard;
    private View audioPlaybackText;
    private View contentScroll;
    private View remoteControls;
    private Button modeStreamerButton;
    private Button modeReceiverButton;
    private Button streamToggleButton;
    private Button standbyToggleButton;
    private Button pipButton;
    private Button torchButton;
    private Button talkButton;
    private RadioButton videoAudioRadio;
    private RadioButton audioOnlyRadio;
    private TextView videoResolutionLabel;
    private Spinner videoResolutionSpinner;
    private Spinner nearbyDevicesSpinner;
    private Spinner remoteResolutionSpinner;
    private TextView streamStatusText;
    private TextView streamMessageText;
    private TextView endpointText;
    private TextView receiverStateText;
    private TextView receiverUrlText;
    private TextView streamerBatteryText;
    private TextView remoteFeaturesNotice;
    private TextView zoomLabel;
    private EditText receiverHostInput;
    private EditText receiverUsernameInput;
    private EditText receiverPasswordInput;
    private CheckBox listenOnlyCheck;
    private SeekBar zoomSeek;
    private PlayerView playerView;
    private PlayerView pipPlayerView;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController mediaController;
    private Dialog fullscreenDialog;
    private Uri selectedAlarmSound;
    private TextView selectedAlarmSoundLabel;
    private int pendingAction = PENDING_NONE;
    private LocalDeviceDiscovery.Browser deviceBrowser;
    private LocalDeviceDiscovery.Device[] nearbyDevices = new LocalDeviceDiscovery.Device[0];
    private boolean receiverMode;
    private boolean updatingRemoteUi;
    private boolean ignoreNextTalkClick;
    private boolean activityStarted;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RtspCameraService.ACTION_STATUS.equals(intent.getAction())) {
                showStreamStatus(intent.getStringExtra(RtspCameraService.EXTRA_STATUS),
                        intent.getStringExtra(RtspCameraService.EXTRA_MESSAGE));
            } else if (ReceiverService.ACTION_STATUS.equals(intent.getAction())) {
                showReceiverStatus(intent.getStringExtra(ReceiverService.EXTRA_STATUS),
                        intent.getStringExtra(ReceiverService.EXTRA_MESSAGE));
            } else if (ReceiverService.ACTION_CONTROL_RESULT.equals(intent.getAction())) {
                String response = intent.getStringExtra(ReceiverService.EXTRA_RESPONSE);
                if (response != null && response.startsWith("ERROR")) {
                    Toast.makeText(MainActivity.this, friendlyControlError(response),
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        bindViews();
        restoreInputs();
        wireActions();
        String mode = AppSettings.preferences(this).getString(KEY_UI_MODE, MODE_STREAM);
        showMode(MODE_RECEIVE.equals(mode));
        showStreamStatus(RtspCameraService.getCurrentStatus(),
                RtspCameraService.getCurrentMessage());
        showReceiverStatus(ReceiverService.getCurrentStatus(),
                ReceiverService.getCurrentMessage());
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        View content = findViewById(R.id.content_root);
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(left + bars.left, top + bars.top,
                        right + bars.right, bottom + bars.bottom);
            } else {
                view.setPadding(left + insets.getSystemWindowInsetLeft(),
                        top + insets.getSystemWindowInsetTop(),
                        right + insets.getSystemWindowInsetRight(),
                        bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        content.requestApplyInsets();
    }

    private void bindViews() {
        streamerPanel = findViewById(R.id.streamer_panel);
        receiverPanel = findViewById(R.id.receiver_panel);
        receiverSetupCard = findViewById(R.id.receiver_setup_card);
        receiverConnectionCard = findViewById(R.id.receiver_connection_card);
        audioPlaybackText = findViewById(R.id.audio_playback_text);
        contentScroll = findViewById(R.id.content_scroll);
        remoteControls = findViewById(R.id.remote_controls);
        modeStreamerButton = findViewById(R.id.mode_streamer_button);
        modeReceiverButton = findViewById(R.id.mode_receiver_button);
        streamToggleButton = findViewById(R.id.stream_toggle_button);
        standbyToggleButton = findViewById(R.id.standby_toggle_button);
        pipButton = findViewById(R.id.pip_button);
        torchButton = findViewById(R.id.torch_button);
        talkButton = findViewById(R.id.talk_button);
        videoAudioRadio = findViewById(R.id.video_audio_radio);
        audioOnlyRadio = findViewById(R.id.audio_only_radio);
        videoResolutionLabel = findViewById(R.id.video_resolution_label);
        videoResolutionSpinner = findViewById(R.id.video_resolution_spinner);
        nearbyDevicesSpinner = findViewById(R.id.nearby_devices_spinner);
        remoteResolutionSpinner = findViewById(R.id.remote_resolution_spinner);
        streamStatusText = findViewById(R.id.stream_status_text);
        streamMessageText = findViewById(R.id.stream_message_text);
        endpointText = findViewById(R.id.endpoint_text);
        receiverStateText = findViewById(R.id.receiver_state_text);
        receiverUrlText = findViewById(R.id.receiver_url_text);
        streamerBatteryText = findViewById(R.id.streamer_battery_text);
        remoteFeaturesNotice = findViewById(R.id.remote_features_notice);
        zoomLabel = findViewById(R.id.zoom_label);
        receiverHostInput = findViewById(R.id.receiver_host_input);
        receiverUsernameInput = findViewById(R.id.receiver_username_input);
        receiverPasswordInput = findViewById(R.id.receiver_password_input);
        listenOnlyCheck = findViewById(R.id.listen_only_check);
        zoomSeek = findViewById(R.id.zoom_seek);
        playerView = findViewById(R.id.player_view);
        pipPlayerView = null;
    }

    private void restoreInputs() {
        SharedPreferences settings = AppSettings.preferences(this);
        receiverHostInput.setText(settings.getString(AppSettings.KEY_RECEIVER_HOST, ""));
        receiverUsernameInput.setText(settings.getString(AppSettings.KEY_RECEIVER_USERNAME,
                AppSettings.DEFAULT_USERNAME));
        receiverPasswordInput.setText(settings.getString(AppSettings.KEY_RECEIVER_PASSWORD, ""));
        listenOnlyCheck.setChecked(settings.getBoolean(AppSettings.KEY_LISTEN_ONLY, false));
        boolean lastVideo = settings.getBoolean(AppSettings.KEY_LAST_VIDEO_MODE, true);
        videoAudioRadio.setChecked(lastVideo);
        audioOnlyRadio.setChecked(!lastVideo);
        videoResolutionSpinner.setAdapter(spinnerAdapter(new String[]{
                getString(R.string.resolution_480p), getString(R.string.resolution_720p),
                getString(R.string.resolution_1080p)}));
        videoResolutionSpinner.setSelection(indexOfResolution(settings.getInt(
                AppSettings.KEY_VIDEO_RESOLUTION, AppSettings.DEFAULT_VIDEO_RESOLUTION)));
        remoteResolutionSpinner.setAdapter(spinnerAdapter(new String[]{
                getString(R.string.resolution_480p), getString(R.string.resolution_720p),
                getString(R.string.resolution_1080p)}));
        showNearbyDevices(new LocalDeviceDiscovery.Device[0]);
        updateResolutionVisibility();
    }

    @SuppressLint("ClickableViewAccessibility") // performClick plus an accessibility click path below.
    private void wireActions() {
        modeStreamerButton.setOnClickListener(view -> showMode(false));
        modeReceiverButton.setOnClickListener(view -> showMode(true));
        findViewById(R.id.settings_button).setOnClickListener(view -> showSettings());
        streamToggleButton.setOnClickListener(view -> togglePublisher());
        standbyToggleButton.setOnClickListener(view -> toggleArmedStandby());
        videoAudioRadio.setOnCheckedChangeListener((button, checked) -> {
            if (checked) updateResolutionVisibility();
        });
        audioOnlyRadio.setOnCheckedChangeListener((button, checked) -> {
            if (checked) updateResolutionVisibility();
        });
        findViewById(R.id.connect_button).setOnClickListener(view -> requestReceiverStart());
        findViewById(R.id.disconnect_button).setOnClickListener(view -> stopReceiver());
        findViewById(R.id.copy_address_button).setOnClickListener(view -> copyAddress());
        findViewById(R.id.show_qr_button).setOnClickListener(view -> showPairingQr());
        findViewById(R.id.scan_qr_button).setOnClickListener(view -> scanPairingQr());
        findViewById(R.id.refresh_devices_button).setOnClickListener(view -> restartDiscovery());
        nearbyDevicesSpinner.setOnItemSelectedListener(new ItemSelectedListener(position -> {
            if (position > 0 && position - 1 < nearbyDevices.length) {
                applyNearbyDevice(nearbyDevices[position - 1]);
            }
        }));
        pipButton.setOnClickListener(view -> enterPip());
        findViewById(R.id.switch_camera_button).setOnClickListener(
                view -> sendRemoteCommand("CAMERA SWITCH"));
        torchButton.setOnClickListener(view ->
                sendRemoteCommand(ReceiverService.isTorchEnabled() ? "TORCH OFF" : "TORCH ON"));
        zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingRemoteUi) return;
                float max = Math.max(1f, ReceiverService.getMaxZoomRatio());
                float zoom = 1f + progress / 100f * (max - 1f);
                zoomLabel.setText(getString(R.string.zoom_format, zoom));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float max = Math.max(1f, ReceiverService.getMaxZoomRatio());
                float zoom = 1f + seekBar.getProgress() / 100f * (max - 1f);
                sendRemoteCommand(String.format(Locale.US, "ZOOM %.2f", zoom));
            }
        });
        remoteResolutionSpinner.setOnItemSelectedListener(new ItemSelectedListener(position -> {
            if (!updatingRemoteUi && ReceiverService.isRunning()
                    && position >= 0 && position < VIDEO_RESOLUTIONS.length
                    && VIDEO_RESOLUTIONS[position] != ReceiverService.getRemoteResolution()) {
                sendRemoteCommand("RESOLUTION " + VIDEO_RESOLUTIONS[position]);
            }
        }));
        talkButton.setOnClickListener(view -> {
            if (ignoreNextTalkClick) {
                ignoreNextTalkClick = false;
            } else if (ReceiverService.isTalking()) {
                stopTalking();
            } else {
                startTalking();
            }
        });
        talkButton.setOnTouchListener(this::handleTalkTouch);
    }

    private void restoreArmedStandbyIfNeeded() {
        boolean enabled = AppSettings.preferences(this).getBoolean(
                AppSettings.KEY_ARMED_ENABLED, false);
        if (enabled && !RtspCameraService.isArmed() && !RtspCameraService.isRunning()) {
            startForegroundService(new Intent(this, RtspCameraService.class)
                    .setAction(RtspCameraService.ACTION_ARM));
        }
    }

    private void showMode(boolean receiver) {
        receiverMode = receiver;
        streamerPanel.setVisibility(receiver ? View.GONE : View.VISIBLE);
        receiverPanel.setVisibility(receiver ? View.VISIBLE : View.GONE);
        styleModeButton(modeStreamerButton, !receiver);
        styleModeButton(modeReceiverButton, receiver);
        AppSettings.preferences(this).edit()
                .putString(KEY_UI_MODE, receiver ? MODE_RECEIVE : MODE_STREAM).apply();
        if (activityStarted) {
            if (receiver) startDiscovery();
            else stopDiscovery();
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_mode_selected
                : android.R.color.transparent);
        button.setTextColor(getColor(selected ? R.color.ink : R.color.ink_muted));
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        restoreArmedStandbyIfNeeded();
        IntentFilter filter = new IntentFilter();
        filter.addAction(RtspCameraService.ACTION_STATUS);
        filter.addAction(ReceiverService.ACTION_STATUS);
        filter.addAction(ReceiverService.ACTION_CONTROL_RESULT);
        ContextCompat.registerReceiver(this, statusReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        showStreamStatus(RtspCameraService.getCurrentStatus(),
                RtspCameraService.getCurrentMessage());
        showReceiverStatus(ReceiverService.getCurrentStatus(),
                ReceiverService.getCurrentMessage());
        if (ReceiverService.isRunning()) connectMediaController();
        if (receiverMode) startDiscovery();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        unregisterReceiver(statusReceiver);
        stopDiscovery();
        if (fullscreenDialog != null) fullscreenDialog.dismiss();
        if (!isInPictureInPictureMode()) releaseMediaController();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releaseMediaController();
        stopDiscovery();
        super.onDestroy();
    }

    private void releaseMediaController() {
        playerView.setPlayer(null);
        if (pipPlayerView != null) pipPlayerView.setPlayer(null);
        mediaController = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }

    private void connectMediaController() {
        if (controllerFuture != null) return;
        SessionToken token = new SessionToken(this,
                new ComponentName(this, ReceiverService.class));
        ListenableFuture<MediaController> pending = new MediaController.Builder(this, token)
                .buildAsync();
        controllerFuture = pending;
        pending.addListener(() -> {
            try {
                MediaController controller = pending.get();
                if (controllerFuture == pending) {
                    runOnUiThread(() -> {
                        if (!activityStarted || controllerFuture != pending) {
                            controller.release();
                            return;
                        }
                        mediaController = controller;
                        attachPlayer(isInPictureInPictureMode()
                                ? ensurePipPlayerView() : playerView);
                    });
                }
            } catch (ExecutionException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, Runnable::run);
    }

    private void attachPlayer(PlayerView view) {
        view.setPlayer(mediaController);
        view.setFullscreenButtonClickListener(fullscreen -> {
            if (fullscreen) showFullscreenPlayer();
        });
    }

    private PlayerView ensurePipPlayerView() {
        if (pipPlayerView == null) {
            android.view.ViewStub stub = findViewById(R.id.pip_player_stub);
            pipPlayerView = (PlayerView) stub.inflate();
        }
        return pipPlayerView;
    }

    @SuppressWarnings("deprecation")
    private void showFullscreenPlayer() {
        if (mediaController == null || fullscreenDialog != null) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        PlayerView fullscreenView = new PlayerView(this);
        fullscreenView.setUseController(true);
        fullscreenView.setResizeMode(playerView.getResizeMode());
        playerView.setPlayer(null);
        fullscreenView.setPlayer(mediaController);
        fullscreenView.setFullscreenButtonClickListener(ignored -> dialog.dismiss());
        dialog.setContentView(fullscreenView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(ignored -> {
            fullscreenView.setPlayer(null);
            fullscreenDialog = null;
            if (controllerFuture != null) attachPlayer(playerView);
        });
        fullscreenDialog = dialog;
        dialog.show();
        dialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void startDiscovery() {
        if (!activityStarted || !receiverMode || deviceBrowser != null) return;
        deviceBrowser = new LocalDeviceDiscovery.Browser(this,
                new LocalDeviceDiscovery.Listener() {
                    @Override
                    public void onDevicesChanged(LocalDeviceDiscovery.Device[] devices) {
                        runOnUiThread(() -> showNearbyDevices(devices));
                    }

                    @Override
                    public void onDiscoveryError(String message) {
                        runOnUiThread(() -> showNearbyDevices(
                                new LocalDeviceDiscovery.Device[0]));
                    }
                });
        deviceBrowser.start();
    }

    private void stopDiscovery() {
        if (deviceBrowser != null) {
            deviceBrowser.close();
            deviceBrowser = null;
        }
    }

    private void restartDiscovery() {
        stopDiscovery();
        showNearbyDevices(new LocalDeviceDiscovery.Device[0]);
        nearbyDevicesSpinner.postDelayed(this::startDiscovery, 500);
    }

    private void showNearbyDevices(LocalDeviceDiscovery.Device[] devices) {
        nearbyDevices = devices;
        String[] labels = new String[devices.length + 1];
        labels[0] = devices.length == 0
                ? getString(R.string.no_devices_found) : getString(R.string.nearby_devices);
        for (int i = 0; i < devices.length; i++) labels[i + 1] = devices[i].displayName();
        nearbyDevicesSpinner.setAdapter(spinnerAdapter(labels));
    }

    private void applyNearbyDevice(LocalDeviceDiscovery.Device device) {
        receiverHostInput.setText(getString(R.string.host_port_format,
                device.host, device.rtspPort));
        receiverUsernameInput.setText(device.username);
        if (!device.videoCapable) listenOnlyCheck.setChecked(true);
    }

    private void showPairingQr() {
        String host = RtspServer.getLocalIpv4Address();
        if ("127.0.0.1".equals(host)) {
            Toast.makeText(this, "Connect this phone to Wi-Fi first", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences settings = AppSettings.preferences(this);
        int port = RtspCameraService.isRunning() || RtspCameraService.isArmed()
                ? RtspCameraService.getCurrentPort()
                : AppSettings.normalizeStreamPort(settings.getInt(
                        AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT));
        String username = settings.getString(AppSettings.KEY_STREAM_USERNAME,
                AppSettings.DEFAULT_USERNAME);
        Uri pairing = new Uri.Builder().scheme("babycam").authority("pair")
                .appendQueryParameter("host", host)
                .appendQueryParameter("port", Integer.toString(port))
                .appendQueryParameter("user", username)
                .build();
        try {
            BitMatrix matrix = new QRCodeWriter().encode(pairing.toString(),
                    BarcodeFormat.QR_CODE, 720, 720);
            Bitmap bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565);
            for (int y = 0; y < 720; y++) {
                for (int x = 0; x < 720; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            image.setPadding(padding, padding, padding, padding);
            image.setAdjustViewBounds(true);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pairing_qr_title)
                    .setMessage(R.string.pairing_qr_help)
                    .setView(image)
                    .setPositiveButton("Done", null)
                    .show();
        } catch (WriterException error) {
            Toast.makeText(this, "Could not create QR code", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation") // Activity is intentionally framework-only; scanner supports it.
    private void scanPairingQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.scan_pairing_qr))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    private boolean applyPairingCode(String value) {
        if (value == null || value.length() > 1_024) return false;
        try {
            Uri pairing = Uri.parse(value);
            if (!"babycam".equalsIgnoreCase(pairing.getScheme())
                    || !"pair".equalsIgnoreCase(pairing.getAuthority())) return false;
            String host = pairing.getQueryParameter("host");
            String portValue = pairing.getQueryParameter("port");
            String username = pairing.getQueryParameter("user");
            if (!RtspServer.isPrivateIpv4Literal(host) || host.length() > 253
                    || portValue == null || username != null && username.length() > 128) {
                return false;
            }
            int port = Integer.parseInt(portValue);
            if (port < 1024 || port > 65533) return false;
            receiverHostInput.setText(getString(R.string.host_port_format, host, port));
            if (username != null && !username.isEmpty()) receiverUsernameInput.setText(username);
            showMode(true);
            receiverPasswordInput.requestFocus();
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void enterPip() {
        if (!ReceiverService.isRunning() || !ReceiverService.hasVideo()) return;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        Rect source = new Rect();
        if (playerView.getGlobalVisibleRect(source)) builder.setSourceRectHint(source);
        if (Build.VERSION.SDK_INT >= 31) builder.setSeamlessResizeEnabled(true);
        enterPictureInPictureMode(builder.build());
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < 31) return;
        boolean enabled = ReceiverService.isRunning() && ReceiverService.hasVideo();
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .setAutoEnterEnabled(enabled)
                .setSeamlessResizeEnabled(true);
        Rect source = new Rect();
        if (playerView.getGlobalVisibleRect(source)) builder.setSourceRectHint(source);
        setPictureInPictureParams(builder.build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode,
                                              Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        playerView.setPlayer(null);
        if (pipPlayerView != null) pipPlayerView.setPlayer(null);
        contentScroll.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        PlayerView target = inPictureInPictureMode ? ensurePipPlayerView() : playerView;
        if (pipPlayerView != null) {
            pipPlayerView.setVisibility(inPictureInPictureMode ? View.VISIBLE : View.GONE);
        }
        if (mediaController != null) {
            attachPlayer(target);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31 && ReceiverService.isRunning()
                && ReceiverService.hasVideo() && !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    private void sendRemoteCommand(String command) {
        if (!ReceiverService.isRunning()) return;
        String password = AppSettings.preferences(this).getString(
                AppSettings.KEY_RECEIVER_PASSWORD, "");
        if (password == null || password.isEmpty()) {
            Toast.makeText(this, R.string.remote_features_need_password,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_REMOTE_CONTROL)
                .putExtra(ReceiverService.EXTRA_COMMAND, command));
    }

    private boolean handleTalkTouch(View button, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startTalking();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            stopTalking();
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                ignoreNextTalkClick = true;
                button.performClick();
            }
            return true;
        }
        return false;
    }

    private void startTalking() {
        String password = AppSettings.preferences(this).getString(
                AppSettings.KEY_RECEIVER_PASSWORD, "");
        if (password == null || password.isEmpty()) {
            Toast.makeText(this, R.string.remote_features_need_password,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAction = PENDING_TALK;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST);
            return;
        }
        talkButton.setText(R.string.talking);
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_TALK_START));
    }

    private void stopTalking() {
        talkButton.setText(R.string.hold_to_talk);
        if (ReceiverService.isRunning()) {
            startService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_TALK_STOP));
        }
    }

    private static String friendlyControlError(String response) {
        if (response.contains("password_required") || response.contains("UNAUTHORIZED")) {
            return "Remote control password is missing or incorrect";
        }
        if (response.contains("torch_unavailable")) return "Torch is unavailable on this camera";
        if (response.contains("camera_unavailable")) return "Only one camera is available";
        if (response.contains("video_unavailable")) return "This is an audio-only stream";
        return "Remote control is unavailable";
    }

    private void togglePublisher() {
        boolean active = RtspCameraService.isRunning()
                || RtspCameraService.STATUS_STARTING.equals(RtspCameraService.getCurrentStatus());
        if (active) stopPublisher();
        else requestStreamStart();
    }

    private void requestStreamStart() {
        boolean video = videoAudioRadio.isChecked();
        pendingAction = video ? PENDING_VIDEO_STREAM : PENDING_AUDIO_STREAM;
        String[] missing = missingCapturePermissions(video);
        if (missing.length == 0) startPublisher(video);
        else requestPermissions(missing, PERMISSIONS_REQUEST);
    }

    private String[] missingCapturePermissions(boolean video) {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        boolean camera = video && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean notices = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        String[] result = new String[(mic ? 1 : 0) + (camera ? 1 : 0) + (notices ? 1 : 0)];
        int i = 0;
        if (mic) result[i++] = Manifest.permission.RECORD_AUDIO;
        if (camera) result[i++] = Manifest.permission.CAMERA;
        if (notices) result[i] = Manifest.permission.POST_NOTIFICATIONS;
        return result;
    }

    private void startPublisher(boolean video) {
        pendingAction = PENDING_NONE;
        stopReceiver();
        int resolution = selectedVideoResolution();
        AppSettings.preferences(this).edit()
                .putBoolean(AppSettings.KEY_LAST_VIDEO_MODE, video)
                .putInt(AppSettings.KEY_VIDEO_RESOLUTION, resolution).apply();
        startForegroundService(new Intent(this, RtspCameraService.class)
                .setAction(RtspCameraService.ACTION_START)
                .putExtra(RtspCameraService.EXTRA_VIDEO_ENABLED, video)
                .putExtra(RtspCameraService.EXTRA_VIDEO_RESOLUTION, resolution));
    }

    private void stopPublisher() {
        if (RtspCameraService.isRunning() || RtspCameraService.isArmed()
                || RtspCameraService.STATUS_STARTING.equals(
                RtspCameraService.getCurrentStatus())) {
            startService(new Intent(this, RtspCameraService.class)
                    .setAction(RtspCameraService.ACTION_STOP));
        }
    }

    private void requestReceiverStart() {
        saveReceiverInputs();
        if (receiverHostInput.getText().toString().trim().isEmpty()) {
            receiverHostInput.setError("Enter the streaming phone’s local IP address");
            receiverHostInput.requestFocus();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAction = PENDING_RECEIVER;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSIONS_REQUEST);
        } else startReceiver();
    }

    private void startReceiver() {
        pendingAction = PENDING_NONE;
        stopPublisher();
        receiverSetupCard.setVisibility(View.GONE);
        receiverConnectionCard.setVisibility(View.VISIBLE);
        receiverUrlText.setText(receiverHostInput.getText().toString().trim());
        receiverStateText.setVisibility(View.VISIBLE);
        receiverStateText.setText(R.string.connecting);
        playerView.setVisibility(View.GONE);
        audioPlaybackText.setVisibility(View.GONE);
        startForegroundService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_CONNECT));
        connectMediaController();
    }

    private void stopReceiver() {
        if (ReceiverService.isRunning()) {
            startService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_DISCONNECT));
        }
        releaseMediaController();
        receiverSetupCard.setVisibility(View.VISIBLE);
        receiverConnectionCard.setVisibility(View.GONE);
    }

    private void saveReceiverInputs() {
        String username = receiverUsernameInput.getText().toString().trim();
        if (username.isEmpty()) username = AppSettings.DEFAULT_USERNAME;
        AppSettings.preferences(this).edit()
                .putString(AppSettings.KEY_RECEIVER_HOST,
                        receiverHostInput.getText().toString().trim())
                .putString(AppSettings.KEY_RECEIVER_USERNAME, username)
                .putString(AppSettings.KEY_RECEIVER_PASSWORD,
                        receiverPasswordInput.getText().toString())
                .putBoolean(AppSettings.KEY_LISTEN_ONLY, listenOnlyCheck.isChecked()).apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSIONS_REQUEST) return;
        int action = pendingAction;
        pendingAction = PENDING_NONE;
        if (action == PENDING_RECEIVER) {
            startReceiver();
        } else if (action == PENDING_TALK) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Hold the talk button again to speak",
                        Toast.LENGTH_SHORT).show();
            }
        } else if (action == PENDING_ARM) {
            boolean video = videoAudioRadio.isChecked();
            if (capturePermissionsGranted(video)) armStandby();
            else AppSettings.preferences(this).edit()
                    .putBoolean(AppSettings.KEY_ARMED_ENABLED, false).apply();
        } else {
            boolean video = action == PENDING_VIDEO_STREAM;
            if ((action == PENDING_VIDEO_STREAM || action == PENDING_AUDIO_STREAM)
                    && capturePermissionsGranted(video)) {
                startPublisher(video);
            } else if (action != PENDING_NONE) {
                showStreamStatus(RtspCameraService.STATUS_ERROR,
                        video ? "Camera and microphone access are required"
                                : "Microphone access is required");
            }
        }
    }

    private boolean capturePermissionsGranted(boolean video) {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && (!video || checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED);
    }

    private void armStandby() {
        pendingAction = PENDING_NONE;
        boolean video = videoAudioRadio.isChecked();
        AppSettings.preferences(this).edit()
                .putBoolean(AppSettings.KEY_ARMED_ENABLED, true)
                .putBoolean(AppSettings.KEY_LAST_VIDEO_MODE, video)
                .putInt(AppSettings.KEY_VIDEO_RESOLUTION, selectedVideoResolution()).apply();
        startForegroundService(new Intent(this, RtspCameraService.class)
                .setAction(RtspCameraService.ACTION_ARM));
        showStreamStatus(RtspCameraService.getCurrentStatus(),
                RtspCameraService.getCurrentMessage());
    }

    private void toggleArmedStandby() {
        SharedPreferences settings = AppSettings.preferences(this);
        if (settings.getBoolean(AppSettings.KEY_ARMED_ENABLED, false)) {
            settings.edit().putBoolean(AppSettings.KEY_ARMED_ENABLED, false).apply();
            startService(new Intent(this, RtspCameraService.class)
                    .setAction(RtspCameraService.ACTION_DISARM));
            showStreamStatus(RtspCameraService.getCurrentStatus(),
                    RtspCameraService.getCurrentMessage());
            return;
        }
        String password = settings.getString(AppSettings.KEY_STREAM_PASSWORD, "");
        if (password == null || password.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.start_standby)
                    .setMessage("Set a stream password in Settings before starting standby.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(R.string.settings, (dialog, which) -> showSettings())
                    .show();
            return;
        }
        boolean video = videoAudioRadio.isChecked();
        String[] missing = missingCapturePermissions(video);
        if (missing.length == 0) armStandby();
        else {
            pendingAction = PENDING_ARM;
            requestPermissions(missing, PERMISSIONS_REQUEST);
        }
    }

    private int selectedVideoResolution() {
        int position = videoResolutionSpinner.getSelectedItemPosition();
        return position >= 0 && position < VIDEO_RESOLUTIONS.length
                ? VIDEO_RESOLUTIONS[position] : AppSettings.DEFAULT_VIDEO_RESOLUTION;
    }

    private void updateResolutionVisibility() {
        int visibility = videoAudioRadio.isChecked() ? View.VISIBLE : View.GONE;
        videoResolutionLabel.setVisibility(visibility);
        videoResolutionSpinner.setVisibility(visibility);
    }

    private void copyAddress() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("BabyCam stream", endpointText.getText()));
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
    }

    private void showStreamStatus(String status, String message) {
        if (status == null || status.isEmpty()) status = RtspCameraService.STATUS_STOPPED;
        streamStatusText.setText(status);
        streamStatusText.setTextColor(statusColor(status));
        boolean active = RtspCameraService.isRunning()
                || RtspCameraService.STATUS_STARTING.equals(status);
        boolean standbyEnabled = AppSettings.preferences(this).getBoolean(
                AppSettings.KEY_ARMED_ENABLED, false);
        streamToggleButton.setText(active ? R.string.stop_stream : R.string.start_stream);
        streamToggleButton.setVisibility(standbyEnabled && !active ? View.GONE : View.VISIBLE);
        streamToggleButton.setBackgroundResource(active
                ? R.drawable.bg_stop_button : R.drawable.bg_primary_button);
        boolean passwordSet = !AppSettings.preferences(this)
                .getString(AppSettings.KEY_STREAM_PASSWORD, "").isEmpty();
        standbyToggleButton.setText(standbyEnabled ? R.string.stop_standby
                : passwordSet ? R.string.start_standby
                : R.string.start_standby_password_required);
        standbyToggleButton.setBackgroundResource(standbyEnabled
                ? R.drawable.bg_stop_button : R.drawable.bg_secondary_button);
        standbyToggleButton.setTextColor(getColor(standbyEnabled
                ? android.R.color.white : R.color.ink));
        standbyToggleButton.setEnabled(!active || standbyEnabled);
        boolean sourceEditable = !active && !standbyEnabled;
        videoAudioRadio.setEnabled(sourceEditable);
        audioOnlyRadio.setEnabled(sourceEditable);
        videoResolutionSpinner.setEnabled(sourceEditable);
        int port = active || RtspCameraService.isArmed()
                ? RtspCameraService.getCurrentPort()
                : AppSettings.normalizeStreamPort(AppSettings.preferences(this).getInt(
                        AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT));
        endpointText.setText(getString(R.string.stream_url_format,
                RtspServer.getLocalIpv4Address(), port));
        if (message != null && !message.isEmpty()) streamMessageText.setText(message);
        else {
            boolean secured = !AppSettings.preferences(this)
                    .getString(AppSettings.KEY_STREAM_PASSWORD, "").isEmpty();
            streamMessageText.setText(secured ? "Password protected"
                    : "No password set • Configure one in Settings");
        }
    }

    private void showReceiverStatus(String status, String message) {
        boolean active = ReceiverService.isRunning();
        receiverSetupCard.setVisibility(active ? View.GONE : View.VISIBLE);
        receiverConnectionCard.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) {
            updatePipParams();
            releaseMediaController();
            return;
        }
        receiverUrlText.setText(ReceiverService.getDisplayUrl());
        boolean connected = ReceiverService.STATUS_PLAYING.equals(status);
        receiverStateText.setVisibility(connected ? View.GONE : View.VISIBLE);
        if (!connected) {
            receiverStateText.setText(message != null && !message.isEmpty()
                    ? status + " • " + message : status);
        }
        boolean showVideo = connected && ReceiverService.hasVideo();
        playerView.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        audioPlaybackText.setVisibility(connected && !showVideo ? View.VISIBLE : View.GONE);
        pipButton.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        int battery = ReceiverService.getBatteryLevel();
        boolean secured = !AppSettings.preferences(this).getString(
                AppSettings.KEY_RECEIVER_PASSWORD, "").isEmpty();
        remoteFeaturesNotice.setVisibility(secured ? View.GONE : View.VISIBLE);
        streamerBatteryText.setVisibility(secured ? View.VISIBLE : View.GONE);
        if (secured && battery < 0) {
            streamerBatteryText.setText(R.string.battery_waiting);
            streamerBatteryText.setTextColor(getColor(R.color.ink_muted));
        } else if (secured && ReceiverService.isLowBattery()) {
            streamerBatteryText.setText(getString(R.string.battery_low_format, battery));
            streamerBatteryText.setTextColor(getColor(R.color.danger));
        } else if (secured && ReceiverService.isCharging()) {
            streamerBatteryText.setText(getString(R.string.battery_charging_format, battery));
            streamerBatteryText.setTextColor(getColor(R.color.success));
        } else if (secured) {
            streamerBatteryText.setText(getString(R.string.battery_format, battery));
            streamerBatteryText.setTextColor(getColor(R.color.ink));
        }
        boolean showRemoteControls = connected && ReceiverService.hasVideo() && secured;
        remoteControls.setVisibility(showRemoteControls ? View.VISIBLE : View.GONE);
        talkButton.setVisibility(connected && secured ? View.VISIBLE : View.GONE);
        talkButton.setText(ReceiverService.isTalking() ? R.string.talking : R.string.hold_to_talk);
        if (showRemoteControls) updateRemoteControls();
        updatePipParams();
    }

    private void updateRemoteControls() {
        updatingRemoteUi = true;
        try {
            torchButton.setText(ReceiverService.isTorchEnabled()
                    ? R.string.torch_off : R.string.torch_on);
            float max = Math.max(1f, ReceiverService.getMaxZoomRatio());
            float zoom = Math.max(1f, Math.min(max, ReceiverService.getZoomRatio()));
            zoomLabel.setText(getString(R.string.zoom_format, zoom));
            int progress = max <= 1f ? 0 : Math.round((zoom - 1f) / (max - 1f) * 100);
            zoomSeek.setProgress(Math.max(0, Math.min(100, progress)));
            zoomSeek.setEnabled(max > 1.01f);
            remoteResolutionSpinner.setSelection(
                    indexOfResolution(ReceiverService.getRemoteResolution()));
        } finally {
            updatingRemoteUi = false;
        }
    }

    private int statusColor(String status) {
        if (RtspCameraService.STATUS_STREAMING.equals(status)) return getColor(R.color.success);
        if (RtspCameraService.STATUS_CONNECTED.equals(status)) return getColor(R.color.success);
        if (RtspCameraService.STATUS_ERROR.equals(status)) return getColor(R.color.danger);
        if (RtspCameraService.STATUS_STARTING.equals(status)
                || RtspCameraService.STATUS_STANDBY.equals(status)) return getColor(R.color.warning);
        return getColor(R.color.ink);
    }

    private void showSettings() {
        SharedPreferences settings = AppSettings.preferences(this);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText username = content.findViewById(R.id.settings_stream_username);
        EditText password = content.findViewById(R.id.settings_stream_password);
        EditText port = content.findViewById(R.id.settings_stream_port);
        Switch alarmEnabled = content.findViewById(R.id.settings_alarm_enabled);
        Switch lowLatency = content.findViewById(R.id.settings_low_latency);
        Spinner delay = content.findViewById(R.id.settings_alarm_delay);
        SeekBar volume = content.findViewById(R.id.settings_alarm_volume);
        TextView volumeLabel = content.findViewById(R.id.settings_volume_label);
        selectedAlarmSoundLabel = content.findViewById(R.id.settings_alarm_sound_label);

        username.setText(settings.getString(AppSettings.KEY_STREAM_USERNAME,
                AppSettings.DEFAULT_USERNAME));
        password.setText(settings.getString(AppSettings.KEY_STREAM_PASSWORD, ""));
        port.setText(String.format(Locale.getDefault(), "%d", AppSettings.normalizeStreamPort(
                settings.getInt(AppSettings.KEY_STREAM_PORT, AppSettings.DEFAULT_STREAM_PORT))));
        alarmEnabled.setChecked(settings.getBoolean(AppSettings.KEY_ALARM_ENABLED, false));
        lowLatency.setChecked(settings.getBoolean(AppSettings.KEY_LOW_LATENCY, false));
        delay.setAdapter(spinnerAdapter(new String[]{"5 seconds", "10 seconds", "15 seconds",
                "30 seconds", "1 minute", "2 minutes"}));
        delay.setSelection(indexOfDelay(settings.getInt(AppSettings.KEY_ALARM_DELAY_SECONDS,
                AppSettings.DEFAULT_ALARM_DELAY_SECONDS)));
        String savedSound = settings.getString(AppSettings.KEY_ALARM_SOUND_URI, "");
        selectedAlarmSound = savedSound == null || savedSound.isEmpty()
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(savedSound);
        updateAlarmSoundLabel();
        content.findViewById(R.id.settings_alarm_sound_button)
                .setOnClickListener(view -> chooseAlarmSound());
        int savedVolume = settings.getInt(AppSettings.KEY_ALARM_VOLUME,
                AppSettings.DEFAULT_ALARM_VOLUME);
        volume.setProgress(savedVolume);
        updateVolumeLabel(volumeLabel, savedVolume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                updateVolumeLabel(volumeLabel, value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.settings)
                .setView(content).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String savedUsername = username.getText().toString().trim();
                    String savedPasswordValue = password.getText().toString();
                    int savedPort;
                    try {
                        savedPort = Integer.parseInt(port.getText().toString());
                    } catch (NumberFormatException error) {
                        port.setError("Enter a port from 1024 to 65533");
                        return;
                    }
                    if (savedUsername.isEmpty()) {
                        username.setError("Enter a username");
                        return;
                    }
                    if (savedPort < 1024 || savedPort > 65533) {
                        port.setError("Enter a port from 1024 to 65533");
                        return;
                    }
                    boolean standbyEnabled = settings.getBoolean(
                            AppSettings.KEY_ARMED_ENABLED, false);
                    if (standbyEnabled && savedPasswordValue.isEmpty()) {
                        password.setError("Stop standby before removing its password");
                        return;
                    }
                    settings.edit()
                            .putString(AppSettings.KEY_STREAM_USERNAME, savedUsername)
                            .putString(AppSettings.KEY_STREAM_PASSWORD, savedPasswordValue)
                            .putInt(AppSettings.KEY_STREAM_PORT, savedPort)
                            .putBoolean(AppSettings.KEY_LOW_LATENCY, lowLatency.isChecked())
                            .putBoolean(AppSettings.KEY_ALARM_ENABLED, alarmEnabled.isChecked())
                            .putInt(AppSettings.KEY_ALARM_DELAY_SECONDS,
                                    ALARM_DELAYS[delay.getSelectedItemPosition()])
                            .putString(AppSettings.KEY_ALARM_SOUND_URI,
                                    selectedAlarmSound == null ? "" : selectedAlarmSound.toString())
                            .putInt(AppSettings.KEY_ALARM_VOLUME, volume.getProgress()).apply();
                    if (standbyEnabled && !RtspCameraService.isRunning()) {
                        startForegroundService(new Intent(this, RtspCameraService.class)
                                .setAction(RtspCameraService.ACTION_ARM));
                    }
                    showStreamStatus(RtspCameraService.getCurrentStatus(),
                            RtspCameraService.getCurrentMessage());
                    selectedAlarmSoundLabel = null;
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> selectedAlarmSoundLabel = null);
        dialog.show();
    }

    private void chooseAlarmSound() {
        Intent picker = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedAlarmSound)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.choose_alarm_sound));
        startActivityForResult(picker, ALARM_SOUND_REQUEST);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null && !applyPairingCode(scan.getContents())) {
                Toast.makeText(this, R.string.invalid_pairing_qr, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == ALARM_SOUND_REQUEST && resultCode == RESULT_OK && data != null) {
            selectedAlarmSound = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            updateAlarmSoundLabel();
        }
    }

    private void updateAlarmSoundLabel() {
        if (selectedAlarmSoundLabel == null) return;
        String title = getString(R.string.default_alarm_sound);
        if (selectedAlarmSound != null) {
            Ringtone ringtone = RingtoneManager.getRingtone(this, selectedAlarmSound);
            if (ringtone != null) title = ringtone.getTitle(this);
        }
        selectedAlarmSoundLabel.setText(title);
    }

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static int indexOfDelay(int value) {
        for (int i = 0; i < ALARM_DELAYS.length; i++) if (ALARM_DELAYS[i] == value) return i;
        return 2;
    }

    private static int indexOfResolution(int value) {
        int normalized = AppSettings.normalizeVideoResolution(value);
        for (int i = 0; i < VIDEO_RESOLUTIONS.length; i++) {
            if (VIDEO_RESOLUTIONS[i] == normalized) return i;
        }
        return 1;
    }

    private static void updateVolumeLabel(TextView label, int volume) {
        label.setText(label.getResources().getString(R.string.alarm_volume_format, volume));
    }

    private static final class ItemSelectedListener
            implements AdapterView.OnItemSelectedListener {
        private final IntConsumer consumer;

        ItemSelectedListener(IntConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            consumer.accept(position);
        }

        @Override public void onNothingSelected(AdapterView<?> parent) { }
    }
}
