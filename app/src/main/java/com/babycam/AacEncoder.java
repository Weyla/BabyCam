package com.babycam;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.io.IOException;
import java.nio.ByteBuffer;

final class AacEncoder {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 1;
    private static final String MIME_TYPE = "audio/mp4a-latm";

    interface Listener {
        void onAudioFormat(byte[] audioSpecificConfig, int sampleRate, int channels);

        void onAudioAccessUnit(byte[] aac, long presentationTimeUs);

        void onAudioError(String message, Throwable error);
    }

    private final Listener listener;
    private MediaCodec codec;
    private AudioRecord recorder;
    private Thread inputThread;
    private Thread outputThread;
    private volatile boolean running;
    private long samplesQueued;

    AacEncoder(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // The foreground service checks RECORD_AUDIO first.
    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
        // H.264 is required to use a hardware codec. AAC is allowed to use the
        // platform encoder when a handset does not expose a hardware AAC encoder.
        codec = CodecUtils.createAnyEncoder(MIME_TYPE, format);
        codec.start();

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IOException("Microphone does not support 48 kHz mono PCM");
        }
        int bufferSize = Math.max(minBuffer, 4096);
        recorder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("Could not initialize microphone");
        }
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IOException("Microphone did not start");
        }

        running = true;
        samplesQueued = 0;
        outputThread = new Thread(this::drainOutput, "BabyCam-AAC-output");
        inputThread = new Thread(this::feedInput, "BabyCam-AAC-input");
        outputThread.start();
        inputThread.start();
    }

    private void feedInput() {
        byte[] pcm = new byte[2048]; // one AAC frame of 1024 mono PCM samples
        try {
            while (running) {
                int read = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    throw new IOException("Microphone read failed (" + read + ")");
                }
                if (read == 0) {
                    continue;
                }
                int inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex < 0) {
                    continue;
                }
                ByteBuffer input = codec.getInputBuffer(inputIndex);
                if (input == null) {
                    continue;
                }
                input.clear();
                input.put(pcm, 0, read);
                long ptsUs = samplesQueued * 1_000_000L / SAMPLE_RATE;
                samplesQueued += read / 2L;
                codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0);
            }
        } catch (Throwable error) {
            if (running) {
                listener.onAudioError("AAC input stopped: " + error.getMessage(), error);
            }
        }
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
                    MediaFormat format = codec.getOutputFormat();
                    ByteBuffer csd = format.getByteBuffer("csd-0");
                    byte[] config = csd == null ? null
                            : CodecUtils.copyBuffer(csd, csd.position(), csd.remaining());
                    int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
                    int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : CHANNELS;
                    listener.onAudioFormat(config, sampleRate, channels);
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                ByteBuffer output = codec.getOutputBuffer(index);
                if (output != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    byte[] data = CodecUtils.copyBuffer(output, info.offset, info.size);
                    listener.onAudioAccessUnit(CodecUtils.removeAdtsHeader(data), info.presentationTimeUs);
                }
                codec.releaseOutputBuffer(index, false);
            }
        } catch (Throwable error) {
            if (running) {
                listener.onAudioError("AAC encoder stopped: " + error.getMessage(), error);
            }
        }
    }

    synchronized void stop() {
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder.release();
            recorder = null;
        }
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
        join(inputThread);
        join(outputThread);
        inputThread = null;
        outputThread = null;
    }

    private static void join(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
