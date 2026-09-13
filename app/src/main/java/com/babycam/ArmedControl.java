package com.babycam;

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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small authenticated request/response channel for LAN status and remote controls. */
final class ArmedControl {
    private static final String PROTOCOL_V1 = "BABYCAM/1";
    private static final String PROTOCOL_V2 = "BABYCAM/2";
    private static final long MAX_CLOCK_DIFFERENCE_MS = 60_000;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int READ_TIMEOUT_MS = 2_500;
    private static final int MAX_REQUEST_LENGTH = 2_048;
    private static final int MAX_RESPONSE_LENGTH = 2_048;
    private static final int MAX_CONTROL_CLIENTS = 4;

    interface Listener {
        /** Returns an OK payload, or a response beginning with ERROR. */
        String onCommand(String command);
    }

    interface Callback {
        void onResult(String response);
    }

    static final class Server implements Closeable {
        private final int port;
        private final String username;
        private final String password;
        private final Listener listener;
        private final Set<String> recentNonces = new LinkedHashSet<>();
        private final ExecutorService clients = new ThreadPoolExecutor(
                MAX_CONTROL_CLIENTS, MAX_CONTROL_CLIENTS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_CONTROL_CLIENTS), runnable -> {
                    Thread thread = new Thread(runnable, "BabyCam-control-client");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        private volatile boolean running;
        private ServerSocket serverSocket;
        private Thread thread;

        Server(int port, String username, String password, Listener listener) {
            this.port = port;
            this.username = username;
            this.password = password;
            this.listener = listener;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(
                        InetAddress.getByName(RtspServer.getLocalIpv4Address()), port), 8);
            } catch (IOException error) {
                serverSocket.close();
                throw error;
            }
            running = true;
            thread = new Thread(this::acceptLoop, "BabyCam-control");
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
            try (socket) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                ParsedRequest request = parseAndAuthenticate(
                        readLimitedLine(input, MAX_REQUEST_LENGTH));
                String response;
                if (request == null) {
                    response = "UNAUTHORIZED";
                } else {
                    try {
                        String payload = listener.onCommand(request.command);
                        response = payload == null || payload.isEmpty() ? "OK" : payload;
                    } catch (RuntimeException error) {
                        response = "ERROR command_failed";
                    }
                }
                output.write(response);
                output.write('\n');
                output.flush();
            } catch (IOException ignored) {
                // A short-lived LAN request can disappear at any time.
            }
        }

        private ParsedRequest parseAndAuthenticate(String line) {
            if (line == null || password == null || password.isEmpty()) return null;
            if (line.startsWith(PROTOCOL_V2 + " ")) return parseV2(line);
            if (line.startsWith(PROTOCOL_V1 + " ")) return parseV1(line);
            return null;
        }

        private ParsedRequest parseV2(String line) {
            String[] fields = line.split(" ", 6);
            if (fields.length != 6) return null;
            String timestamp = fields[1];
            String nonce = fields[2];
            String encodedUsername = fields[3];
            String encodedCommand = fields[4];
            if (!validTimestamp(timestamp) || !validUsername(encodedUsername)
                    || !validNonce(nonce)) return null;
            String expected = signatureV2(password, timestamp, nonce, encodedUsername,
                    encodedCommand);
            if (!safeEquals(expected, fields[5]) || !rememberNonce(nonce)) return null;
            try {
                String command = new String(Base64.decode(encodedCommand,
                        Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
                return isSafeCommand(command) ? new ParsedRequest(command) : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        private ParsedRequest parseV1(String line) {
            String[] fields = line.split(" ", 6);
            if (fields.length != 6 || !("START".equals(fields[1])
                    || "START_VIDEO".equals(fields[1]) || "START_AUDIO".equals(fields[1]))) {
                return null;
            }
            if (!validTimestamp(fields[2]) || !validUsername(fields[4])
                    || !validNonce(fields[3])) return null;
            String expected = signatureV1(password, fields[1], fields[2], fields[3], fields[4]);
            return safeEquals(expected, fields[5]) && rememberNonce(fields[3])
                    ? new ParsedRequest(fields[1]) : null;
        }

        private boolean validTimestamp(String value) {
            try {
                return Math.abs(System.currentTimeMillis() - Long.parseLong(value))
                        <= MAX_CLOCK_DIFFERENCE_MS;
            } catch (NumberFormatException error) {
                return false;
            }
        }

        private boolean validUsername(String encoded) {
            try {
                return username.equals(new String(Base64.decode(encoded,
                        Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        private static boolean isSafeCommand(String command) {
            return !command.isEmpty() && command.length() <= 256
                    && command.indexOf('\n') < 0 && command.indexOf('\r') < 0;
        }

        private synchronized boolean rememberNonce(String nonce) {
            if (!validNonce(nonce) || recentNonces.contains(nonce)) return false;
            recentNonces.add(nonce);
            while (recentNonces.size() > 256) {
                recentNonces.remove(recentNonces.iterator().next());
            }
            return true;
        }

        @Override
        public void close() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
                serverSocket = null;
            }
            clients.shutdownNow();
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }
    }

    static void requestStartAsync(String host, int port, String username, String password,
                                  boolean videoRequested) {
        requestAsync(host, port, username, password,
                videoRequested ? "START_VIDEO" : "START_AUDIO", null);
    }

    static void requestAsync(String host, int port, String username, String password,
                             String command, Callback callback) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535
                || password == null || password.isEmpty()) {
            if (callback != null) callback.onResult("ERROR password_required");
            return;
        }
        new Thread(() -> {
            String response = request(host, port, username, password, command);
            if (callback != null) callback.onResult(response);
        }, "BabyCam-control-request").start();
    }

    private static String request(String host, int port, String username, String password,
                                  String command) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String encodedUsername = encode(username == null ? "" : username);
        String encodedCommand = encode(command);
        String signature = signatureV2(password, timestamp, nonce, encodedUsername,
                encodedCommand);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            output.write(PROTOCOL_V2 + " " + timestamp + " " + nonce + " "
                    + encodedUsername + " " + encodedCommand + " " + signature + "\n");
            output.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String response = readLimitedLine(input, MAX_RESPONSE_LENGTH);
            return response == null ? "ERROR empty_response" : response;
        } catch (IOException error) {
            return "ERROR unavailable";
        }
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static String signatureV2(String password, String timestamp, String nonce,
                                      String username, String command) {
        return hmac(password, timestamp + "\n" + nonce + "\n" + username + "\n" + command);
    }

    private static String signatureV1(String password, String command, String timestamp,
                                      String nonce, String username) {
        return hmac(password, command + "\n" + timestamp + "\n" + nonce + "\n" + username);
    }

    private static String hmac(String password, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }

    private static boolean safeEquals(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
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
        StringBuilder line = new StringBuilder(Math.min(maximum, 256));
        while (true) {
            int value = input.read();
            if (value < 0) return line.length() == 0 ? null : line.toString();
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > maximum) throw new IOException("Protocol line is too long");
        }
    }

    private static final class ParsedRequest {
        final String command;

        ParsedRequest(String command) {
            this.command = command;
        }
    }

    private ArmedControl() {
    }
}
