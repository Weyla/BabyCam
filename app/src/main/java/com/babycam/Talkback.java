package com.babycam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Password-authenticated, 16 kHz mono PCM push-to-talk channel. */
final class Talkback {
    private static final String PROTOCOL = "BABYCAM-TALK/1";
    private static final int SAMPLE_RATE = 16_000;
    private static final long MAX_CLOCK_DIFFERENCE_MS = 60_000;
    private static final int MAX_AUTH_LINE_LENGTH = 1_024;

    interface Listener {
        void onConnected();

        void onError(String message);
    }

    static final class Server implements Closeable {
        private final int port;
        private final String username;
        private final String password;
        private volatile boolean running;
        private final Set<String> recentNonces = new LinkedHashSet<>();
        private final ExecutorService clients = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                    Thread worker = new Thread(runnable, "BabyCam-talkback-client");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        private ServerSocket serverSocket;
        private Socket activeSocket;
        private Thread thread;

        Server(int port, String username, String password) {
            this.port = port;
            this.username = username;
            this.password = password;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket(port, 2,
                    InetAddress.getByName(RtspServer.getLocalIpv4Address()));
            running = true;
            thread = new Thread(this::acceptLoop, "BabyCam-talkback-server");
            thread.start();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    try {
                        clients.execute(() -> handle(socket));
                    } catch (RejectedExecutionException overloaded) {
                        socket.close();
                    }
                } catch (IOException ignored) {
                    // Closing the server is the normal way to stop this loop.
                }
            }
        }

        private void handle(Socket socket) {
            try {
                play(socket);
            } catch (IOException ignored) {
                // A released talk button or server shutdown ends the current connection.
            } finally {
                synchronized (this) {
                    if (activeSocket == socket) activeSocket = null;
                }
            }
        }

        private void play(Socket socket) throws IOException {
            try (socket) {
                socket.setSoTimeout(3_000);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String request = readLimitedLine(input, MAX_AUTH_LINE_LENGTH);
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                if (!authenticate(request)) {
                    output.write("UNAUTHORIZED\n");
                    output.flush();
                    return;
                }
                synchronized (this) {
                    if (!running) return;
                    if (activeSocket != null && activeSocket != socket) activeSocket.close();
                    activeSocket = socket;
                }
                output.write("OK\n");
                output.flush();
                socket.setSoTimeout(0);
                int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minimum, SAMPLE_RATE / 5);
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                try {
                    track.play();
                    byte[] buffer = new byte[Math.max(1024, bufferSize / 2)];
                    int count;
                    while (running && (count = socket.getInputStream().read(buffer)) >= 0) {
                        if (count > 0) track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING);
                    }
                } finally {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    track.release();
                }
            }
        }

        private boolean authenticate(String request) {
            if (request == null || password == null || password.isEmpty()) return false;
            String[] fields = request.split(" ", 5);
            if (fields.length != 5 || !PROTOCOL.equals(fields[0])) return false;
            try {
                if (Math.abs(System.currentTimeMillis() - Long.parseLong(fields[1]))
                        > MAX_CLOCK_DIFFERENCE_MS) return false;
                String suppliedUser = new String(Base64.decode(fields[3],
                        Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
                if (!username.equals(suppliedUser)) return false;
            } catch (IllegalArgumentException error) {
                return false;
            }
            if (!validNonce(fields[2])) return false;
            String expected = signature(password, fields[1], fields[2], fields[3]);
            boolean verified = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    fields[4].toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
            return verified && rememberNonce(fields[2]);
        }

        private synchronized boolean rememberNonce(String nonce) {
            if (!validNonce(nonce) || recentNonces.contains(nonce)) return false;
            recentNonces.add(nonce);
            while (recentNonces.size() > 128) {
                recentNonces.remove(recentNonces.iterator().next());
            }
            return true;
        }

        @Override
        public void close() {
            Thread serverThread;
            synchronized (this) {
                running = false;
                if (activeSocket != null) {
                    try {
                        activeSocket.close();
                    } catch (IOException ignored) {
                    }
                    activeSocket = null;
                }
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {
                    }
                    serverSocket = null;
                }
                serverThread = thread;
                thread = null;
            }
            clients.shutdownNow();
            if (serverThread != null && serverThread != Thread.currentThread()) {
                try {
                    serverThread.join(750);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static final class Client implements Closeable {
        private final Context context;
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final Listener listener;
        private volatile boolean running;
        private Socket socket;
        private AudioRecord recorder;
        private Thread thread;

        Client(Context context, String host, int port, String username, String password,
               Listener listener) {
            this.context = context.getApplicationContext();
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.listener = listener;
        }

        void start() {
            if (running) return;
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                listener.onError("Microphone permission is required");
                return;
            }
            running = true;
            thread = new Thread(this::recordLoop, "BabyCam-talkback-client");
            thread.start();
        }

        @SuppressWarnings("MissingPermission")
        private void recordLoop() {
            try {
                if (!running) return;
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 1_500);
                if (!running) return;
                socket.setSoTimeout(2_500);
                String timestamp = Long.toString(System.currentTimeMillis());
                String nonce = UUID.randomUUID().toString().replace("-", "");
                String encodedUsername = Base64.encodeToString(
                        username.getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP);
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                output.write(PROTOCOL + " " + timestamp + " " + nonce + " "
                        + encodedUsername + " "
                        + signature(password, timestamp, nonce, encodedUsername) + "\n");
                output.flush();
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                if (!"OK".equals(readLimitedLine(input, 64))) {
                    throw new IOException("Talkback authentication failed");
                }
                if (!running) return;
                socket.setSoTimeout(0);
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minimum, SAMPLE_RATE / 5);
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("Could not open receiver microphone");
                }
                recorder.startRecording();
                listener.onConnected();
                byte[] buffer = new byte[Math.max(1024, bufferSize / 2)];
                while (running) {
                    int count = recorder.read(buffer, 0, buffer.length);
                    if (count > 0) socket.getOutputStream().write(buffer, 0, count);
                    else if (count < 0) throw new IOException("Could not read receiver microphone");
                }
            } catch (IOException | SecurityException | IllegalStateException
                     | IllegalArgumentException error) {
                if (running) listener.onError(error.getMessage() == null
                        ? "Talkback is unavailable" : error.getMessage());
            } finally {
                releaseResources();
                running = false;
            }
        }

        @Override
        public void close() {
            running = false;
            releaseResources();
            Thread activeThread = thread;
            if (activeThread != null && activeThread != Thread.currentThread()) {
                try {
                    activeThread.join(750);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }

        private synchronized void releaseResources() {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (IllegalStateException ignored) {
                }
                recorder.release();
                recorder = null;
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                socket = null;
            }
        }
    }

    private static String signature(String password, String timestamp, String nonce,
                                    String encodedUsername) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("TALK\n" + timestamp + "\n" + nonce + "\n"
                    + encodedUsername).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }

    private static boolean validNonce(String nonce) {
        if (nonce == null || nonce.length() < 8 || nonce.length() > 64) return false;
        for (int index = 0; index < nonce.length(); index++) {
            char item = nonce.charAt(index);
            if (!Character.isLetterOrDigit(item) && item != '-' && item != '_') return false;
        }
        return true;
    }

    private static String readLimitedLine(BufferedReader input, int maximum) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maximum, 128));
        while (true) {
            int value = input.read();
            if (value < 0) return line.length() == 0 ? null : line.toString();
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > maximum) throw new IOException("Protocol line is too long");
        }
    }

    private Talkback() {
    }
}
