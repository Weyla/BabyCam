package com.babycam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

final class H264Encoder {
    static final int FRAME_RATE = 20;
    private static final String MIME_TYPE = "video/avc";

    interface Listener {
        void onVideoFormat(byte[] sps, byte[] pps);

        void onVideoAccessUnit(List<byte[]> nals, long presentationTimeUs,
                               boolean keyFrame, boolean codecConfig);

        void onVideoError(String message, Throwable error);
    }

    private final Listener listener;
    private final int width;
    private final int height;
    private final int bitRate;
    private final int level;
    private final boolean lowLatency;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread outputThread;
    private volatile boolean running;
    private byte[] sps;
    private byte[] pps;

    H264Encoder(Listener listener, int verticalResolution, boolean lowLatency) {
        this.listener = listener;
        this.lowLatency = lowLatency;
        int resolution = AppSettings.normalizeVideoResolution(verticalResolution);
        if (resolution == 480) {
            width = 640;
            height = 480;
            bitRate = 1_000_000;
            level = MediaCodecInfo.CodecProfileLevel.AVCLevel3;
        } else if (resolution == 1080) {
            width = 1920;
            height = 1080;
            bitRate = 5_000_000;
            level = MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        } else {
            width = 1280;
            height = 720;
            bitRate = 2_500_000;
            level = MediaCodecInfo.CodecProfileLevel.AVCLevel31;
        }
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // The Qualcomm encoder on this phone otherwise reports Level 1.0 even
        // for 1280x720 output. That is not a valid level for this frame size
        // and causes browser MSE players to reject the stream.
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, level);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, lowLatency ? 1 : 2);
        if (lowLatency) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
            if (Build.VERSION.SDK_INT >= 29) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
        }
        codec = CodecUtils.createHardwareEncoder(MIME_TYPE, format);
        inputSurface = codec.createInputSurface();
        codec.start();
        running = true;
        outputThread = new Thread(this::drainOutput, "BabyCam-H264-output");
        outputThread.start();
    }

    Surface getInputSurface() {
        return inputSurface;
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    updateFormat(codec.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] data = CodecUtils.copyBuffer(buffer, info.offset, info.size);
                    List<byte[]> nals = CodecUtils.extractNals(data);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (config) {
                        updateNals(nals);
                    }
                    if (!nals.isEmpty()) {
                        listener.onVideoAccessUnit(nals, info.presentationTimeUs,
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, config);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }
        } catch (Throwable error) {
            if (running) {
                listener.onVideoError("H.264 encoder stopped: " + error.getMessage(), error);
            }
        }
    }

    private void updateFormat(MediaFormat format) {
        List<byte[]> nals = CodecUtils.extractCodecNals(format);
        updateNals(nals);
    }

    private synchronized void updateNals(List<byte[]> nals) {
        byte[] newSps = CodecUtils.findNalType(nals, 7);
        byte[] newPps = CodecUtils.findNalType(nals, 8);
        if (newSps != null) {
            sps = newSps;
        }
        if (newPps != null) {
            pps = newPps;
        }
        if (newSps != null || newPps != null) {
            listener.onVideoFormat(sps, pps);
        }
    }

    synchronized void stop() {
        running = false;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (outputThread != null && outputThread != Thread.currentThread()) {
            try {
                outputThread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            outputThread = null;
        }
    }
}
