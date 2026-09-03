package com.babycam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;

import java.util.Collections;

/** Owns Camera2 state and serializes all capture changes on one handler thread. */
final class CameraController {
    interface Listener {
        void onCameraError(CameraController source, String message, Throwable error);
    }

    private final Context context;
    private final Surface encoderSurface;
    private final Listener listener;
    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder requestBuilder;
    private CameraCharacteristics characteristics;
    private volatile int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private volatile boolean torchEnabled;
    private volatile float zoomRatio = 1f;
    private volatile float maxZoomRatio = 1f;
    private volatile boolean stopped;

    CameraController(Context context, Surface encoderSurface, Listener listener) {
        this.context = context.getApplicationContext();
        this.encoderSurface = encoderSurface;
        this.listener = listener;
    }

    void start() throws CameraAccessException {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Camera permission was not granted");
        }
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (findCamera(lensFacing) == null) {
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT;
        }
        if (findCamera(lensFacing) == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                    "No camera is available");
        }
        cameraThread = new HandlerThread("BabyCam-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        openSelectedCamera();
    }

    boolean hasFrontCamera() {
        try {
            return cameraManager != null
                    && findCamera(CameraCharacteristics.LENS_FACING_FRONT) != null;
        } catch (CameraAccessException error) {
            return false;
        }
    }

    boolean hasBackCamera() {
        try {
            return cameraManager != null
                    && findCamera(CameraCharacteristics.LENS_FACING_BACK) != null;
        } catch (CameraAccessException error) {
            return false;
        }
    }

    boolean hasTorch() {
        Boolean available = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        return lensFacing == CameraCharacteristics.LENS_FACING_BACK
                && Boolean.TRUE.equals(available);
    }

    int getLensFacing() {
        return lensFacing;
    }

    boolean isTorchEnabled() {
        return torchEnabled;
    }

    float getZoomRatio() {
        return zoomRatio;
    }

    float getMaxZoomRatio() {
        return maxZoomRatio;
    }

    void switchCamera() {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            int requested = lensFacing == CameraCharacteristics.LENS_FACING_BACK
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            try {
                if (findCamera(requested) == null) return;
                lensFacing = requested;
                torchEnabled = false;
                zoomRatio = 1f;
                closeCamera();
                openSelectedCamera();
            } catch (CameraAccessException | SecurityException error) {
                listener.onCameraError(this, "Could not switch camera", error);
            }
        });
    }

    void setTorch(boolean enabled) {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            torchEnabled = enabled && hasTorch();
            applyRepeatingRequest();
        });
    }

    void setZoomRatio(float requestedRatio) {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            zoomRatio = Math.max(1f, Math.min(maxZoomRatio, requestedRatio));
            applyRepeatingRequest();
        });
    }

    private String findCamera(int facingRequested) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics item = cameraManager.getCameraCharacteristics(id);
            Integer facing = item.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap outputs = item.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (facing != null && facing == facingRequested && outputs != null
                    && outputs.isOutputSupportedFor(encoderSurface)) {
                return id;
            }
        }
        return null;
    }

    private void openSelectedCamera() throws CameraAccessException, SecurityException {
        if (stopped) return;
        String id = findCamera(lensFacing);
        if (id == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                "Selected camera is unavailable");
        characteristics = cameraManager.getCameraCharacteristics(id);
        updateZoomCapabilities();
        cameraManager.openCamera(id, stateCallback, cameraHandler);
    }

    private void updateZoomCapabilities() {
        maxZoomRatio = 1f;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> range = characteristics.get(
                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null) maxZoomRatio = Math.max(1f, range.getUpper());
        } else {
            Float digital = characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (digital != null) maxZoomRatio = Math.max(1f, digital);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            if (stopped) {
                cameraDevice.close();
                return;
            }
            camera = cameraDevice;
            configureCapture();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            if (!stopped) listener.onCameraError(CameraController.this,
                    "Camera disconnected", null);
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            if (!stopped) listener.onCameraError(CameraController.this,
                    "Camera error " + error, null);
        }
    };

    private void configureCapture() {
        try {
            requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            requestBuilder.addTarget(encoderSurface);
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            CameraCaptureSession.StateCallback callback =
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (stopped) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            applyRepeatingRequest();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            if (!stopped) listener.onCameraError(CameraController.this,
                                    "Camera capture session configuration failed", null);
                        }
                    };
            createCaptureSession(camera, callback);
        } catch (CameraAccessException | RuntimeException error) {
            if (!stopped) listener.onCameraError(this, "Could not configure camera", error);
        }
    }

    private void applyRepeatingRequest() {
        if (requestBuilder == null || captureSession == null || characteristics == null) return;
        try {
            Boolean flashAvailable = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flashAvailable)) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, torchEnabled && hasTorch()
                        ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
            } else {
                Rect sensor = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (sensor != null) {
                    int width = Math.round(sensor.width() / zoomRatio);
                    int height = Math.round(sensor.height() / zoomRatio);
                    int left = sensor.left + (sensor.width() - width) / 2;
                    int top = sensor.top + (sensor.height() - height) / 2;
                    requestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                            new Rect(left, top, left + width, top + height));
                }
            }
            captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException error) {
            if (!stopped) listener.onCameraError(this,
                    "Could not update camera controls", error);
        }
    }

    @SuppressWarnings("deprecation") // API 26–27 require the legacy overload.
    private void createCaptureSession(CameraDevice cameraDevice,
                                      CameraCaptureSession.StateCallback callback)
            throws CameraAccessException {
        if (Build.VERSION.SDK_INT >= 28) {
            SessionConfiguration configuration = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(encoderSurface)),
                    command -> {
                        Handler handler = cameraHandler;
                        if (handler != null) handler.post(command);
                    }, callback);
            cameraDevice.createCaptureSession(configuration);
        } else {
            cameraDevice.createCaptureSession(Collections.singletonList(encoderSurface),
                    callback, cameraHandler);
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        requestBuilder = null;
        if (camera != null) {
            camera.close();
            camera = null;
        }
        characteristics = null;
    }

    void stop() {
        stopped = true;
        Handler handler = cameraHandler;
        boolean calledFromCameraThread = cameraThread != null
                && Thread.currentThread() == cameraThread;
        if (handler != null && !calledFromCameraThread) {
            handler.post(this::closeCamera);
        } else {
            closeCamera();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            if (!calledFromCameraThread) {
                try {
                    cameraThread.join(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }
}
