package com.babycam;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RtspServer implements H264Encoder.Listener, AacEncoder.Listener {
    interface Listener {
        void onStreamError(RtspServer source, String message, Throwable error);

        void onPlayingClientCountChanged(RtspServer source, int clientCount);
    }

    static final int DEFAULT_PORT = 8554;
    private static final int VIDEO_PAYLOAD_TYPE = 96;
    private static final int AUDIO_PAYLOAD_TYPE = 97;
    private static final int MAX_RTP_PAYLOAD = 1200;
    private static final int MAX_CLIENTS = 8;
    private static final int SOCKET_READ_TIMEOUT_MS = 60_000;
    private static final int MAX_LINE_LENGTH = 8_192;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_REQUEST_BODY = 65_536;
    private static final int OUTBOUND_QUEUE_CAPACITY = 256;

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger playingClientCount = new AtomicInteger();
    private final boolean videoEnabled;
    private final String password;
    private final String username;
    private final Listener errorListener;
    private final int port;
    private final String authenticationNonce = UUID.randomUUID().toString().replace("-", "");
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile byte[] audioSpecificConfig = new byte[]{0x11, (byte) 0x88};
    private volatile int audioSampleRate = AacEncoder.SAMPLE_RATE;
    private volatile int audioChannels = AacEncoder.CHANNELS;
    private volatile List<byte[]> videoConfigNals = Collections.emptyList();
    private volatile List<byte[]> lastVideoKeyframe;
    private volatile long lastVideoKeyframePtsUs;
    private long videoPtsBaseUs = Long.MIN_VALUE;

    RtspServer(boolean videoEnabled, String username, String password, int port,
               Listener errorListener) {
        this.videoEnabled = videoEnabled;
        this.username = username;
        this.password = password == null ? "" : password;
        this.port = port;
        this.errorListener = errorListener;
    }

    void start() throws IOException {
        if (running) {
            return;
        }
        // Bind only to a private IPv4 interface. This app deliberately does not
        // expose the RTSP server on a public/cellular address.
        serverSocket = new ServerSocket(port, 16, InetAddress.getByName(getLocalIpv4Address()));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "BabyCam-RTSP-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (clients.size() >= MAX_CLIENTS) {
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                Client client = new Client(socket);
                clients.add(client);
                new Thread(client, "BabyCam-RTSP-client").start();
            } catch (IOException error) {
                if (running) {
                    running = false;
                    errorListener.onStreamError(this,
                            "The local streaming server stopped", error);
                }
            }
        }
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try {
                acceptThread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
    }

    int getPlayingClientCount() {
        return playingClientCount.get();
    }

    @Override
    public synchronized void onVideoFormat(byte[] newSps, byte[] newPps) {
        if (newSps != null) {
            sps = newSps.clone();
        }
        if (newPps != null) {
            pps = newPps.clone();
        }
        updateVideoConfigNals();
        notifyAll();
    }

    @Override
    public void onVideoAccessUnit(List<byte[]> nals, long presentationTimeUs,
                                  boolean keyFrame, boolean codecConfig) {
        // Some encoders mark SPS/PPS as codec-config output, while others
        // prepend them to an IDR access unit. Handle both forms so DESCRIBE
        // can always advertise sprop-parameter-sets to Frigate/go2rtc.
        byte[] configSps = CodecUtils.findNalType(nals, 7);
        byte[] configPps = CodecUtils.findNalType(nals, 8);
        if (configSps != null || configPps != null) {
            if (configSps != null) {
                sps = configSps.clone();
            }
            if (configPps != null) {
                pps = configPps.clone();
            }
            updateVideoConfigNals();
            synchronized (this) {
                notifyAll();
            }
        }
        if (codecConfig) {
            videoConfigNals = immutableCopy(nals);
        }
        // SPS/PPS are advertised in SDP. Do not forward them as ordinary
        // media samples: go2rtc's fMP4 muxer may otherwise put them into the
        // first IDR sample, which makes Chromium's MSE buffer reject it.
        List<byte[]> mediaNals = withoutParameterSets(nals);
        if (mediaNals.isEmpty()) {
            return;
        }
        // Surface-input video encoders commonly report timestamps from the
        // camera/boot clock, while AudioRecord input starts at zero. RTSP
        // clients need both tracks on the same timeline or MSE may discard
        // the audio fragments as being outside the video range. Do not use a
        // codec-config buffer as the origin: its timestamp may precede the
        // first actual video frame by several seconds on some devices.
        long streamPtsUs = normalizeVideoPts(presentationTimeUs);
        boolean actualKeyFrame = keyFrame || CodecUtils.findNalType(mediaNals, 5) != null;
        if (actualKeyFrame) {
            lastVideoKeyframe = immutableCopy(mediaNals);
            lastVideoKeyframePtsUs = streamPtsUs;
        }
        for (Client client : clients) {
            if (!client.isPlaying()) {
                continue;
            }
            try {
                client.sendVideo(mediaNals, streamPtsUs);
            } catch (IOException error) {
                client.close();
            }
        }
    }

    private static List<byte[]> withoutParameterSets(List<byte[]> nals) {
        ArrayList<byte[]> media = new ArrayList<>(nals.size());
        for (byte[] nal : nals) {
            int type = nal.length == 0 ? -1 : (nal[0] & 0x1f);
            if (type != 7 && type != 8) {
                media.add(nal);
            }
        }
        return media;
    }

    private synchronized long normalizeVideoPts(long presentationTimeUs) {
        if (videoPtsBaseUs == Long.MIN_VALUE) {
            videoPtsBaseUs = presentationTimeUs;
        }
        return Math.max(0L, presentationTimeUs - videoPtsBaseUs);
    }

    @Override
    public void onVideoError(String message, Throwable error) {
        errorListener.onStreamError(this, message, error);
    }

    @Override
    public void onAudioFormat(byte[] config, int sampleRate, int channels) {
        if (config != null && config.length > 0) {
            audioSpecificConfig = config.clone();
        }
        audioSampleRate = sampleRate;
        audioChannels = channels;
    }

    @Override
    public void onAudioAccessUnit(byte[] aac, long presentationTimeUs) {
        if (aac == null || aac.length == 0) {
            return;
        }
        for (Client client : clients) {
            if (!client.isPlaying()) {
                continue;
            }
            try {
                client.sendAudio(aac, presentationTimeUs);
            } catch (IOException error) {
                client.close();
            }
        }
    }

    @Override
    public void onAudioError(String message, Throwable error) {
        errorListener.onStreamError(this, message, error);
    }

    private static List<byte[]> immutableCopy(List<byte[]> source) {
        ArrayList<byte[]> copy = new ArrayList<>(source.size());
        for (byte[] nal : source) {
            copy.add(nal.clone());
        }
        return Collections.unmodifiableList(copy);
    }

    private synchronized void waitForVideoConfig() {
        if (sps != null && pps != null) {
            return;
        }
        try {
            wait(3000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void updateVideoConfigNals() {
        ArrayList<byte[]> config = new ArrayList<>(2);
        if (sps != null) {
            config.add(sps.clone());
        }
        if (pps != null) {
            config.add(pps.clone());
        }
        videoConfigNals = Collections.unmodifiableList(config);
    }

    private String buildSdp() {
        if (videoEnabled) {
            waitForVideoConfig();
        }
        byte[] currentSps = sps;
        String profile = "42e01f";
        if (currentSps != null && currentSps.length >= 4) {
            profile = String.format(Locale.US, "%02x%02x%02x",
                    currentSps[1] & 0xff, currentSps[2] & 0xff, currentSps[3] & 0xff);
        }
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 0 IN IP4 ").append(getLocalIpv4Address()).append("\r\n");
        sdp.append("s=BabyCam\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=control:*\r\n");
        sdp.append("a=range:npt=0-\r\n");
        if (videoEnabled) {
            sdp.append("m=video 0 RTP/AVP ").append(VIDEO_PAYLOAD_TYPE).append("\r\n");
            sdp.append("c=IN IP4 0.0.0.0\r\n");
            sdp.append("a=rtpmap:").append(VIDEO_PAYLOAD_TYPE).append(" H264/90000\r\n");
            sdp.append("a=fmtp:").append(VIDEO_PAYLOAD_TYPE)
                    .append(" packetization-mode=1;profile-level-id=").append(profile);
            if (sps != null && pps != null) {
                sdp.append(";sprop-parameter-sets=")
                        .append(CodecUtils.base64(sps)).append(',').append(CodecUtils.base64(pps));
            }
            sdp.append("\r\n");
            sdp.append("a=control:trackID=0\r\n");
        }
        sdp.append("m=audio 0 RTP/AVP ").append(AUDIO_PAYLOAD_TYPE).append("\r\n");
        sdp.append("c=IN IP4 0.0.0.0\r\n");
        sdp.append("a=rtpmap:").append(AUDIO_PAYLOAD_TYPE).append(" MPEG4-GENERIC/")
                .append(audioSampleRate).append('/').append(audioChannels).append("\r\n");
        sdp.append("a=fmtp:").append(AUDIO_PAYLOAD_TYPE)
                .append(" streamtype=5;profile-level-id=15;mode=AAC-hbr;")
                .append("sizelength=13;indexlength=3;indexdeltalength=3;config=")
                .append(CodecUtils.hex(audioSpecificConfig)).append("\r\n");
        sdp.append("a=control:trackID=1\r\n");
        return sdp.toString();
    }

    static String getLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return "127.0.0.1";
    }

    static boolean isPrivateIpv4Literal(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty() || parts[index].length() > 3) return false;
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }

    private static int[] parsePair(String value, String key) {
        Matcher matcher = Pattern.compile(key + "\\s*=\\s*(\\d+)-(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(value == null ? "" : value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean validPair(int[] pair, int minimum, int maximum) {
        return pair != null && pair[0] >= minimum && pair[0] <= maximum
                && pair[1] >= minimum && pair[1] <= maximum && pair[0] != pair[1];
    }

    private final class Client implements Runnable, Closeable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final Object outputLock = new Object();
        private final BlockingQueue<OutboundPacket> outbound =
                new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
        private final Thread writerThread;
        private volatile boolean alive = true;
        private volatile boolean playing;
        private boolean countedPlaybackSession;
        private String session;
        private TrackTransport video;
        private TrackTransport audio;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
            writerThread = new Thread(this::writeLoop, "BabyCam-RTSP-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }

        boolean isPlaying() {
            return alive && playing;
        }

        @Override
        public void run() {
            try {
                while (alive) {
                    int first = input.read();
                    if (first < 0) {
                        break;
                    }
                    if (first == '$') {
                        skipInterleavedPacket();
                        continue;
                    }
                    Request request = readRequest(first);
                    if (request == null || !handle(request)) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                // Disconnects are normal for RTSP clients.
            } finally {
                close();
            }
        }

        private boolean handle(Request request) throws IOException {
            String method = request.method;
            if ("OPTIONS".equals(method)) {
                sendResponse(request.cseq, 200, "OK",
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n",
                        null);
                return true;
            }
            if (!isAuthorized(request)) {
                sendResponse(request.cseq, 401, "Unauthorized",
                        "WWW-Authenticate: Digest realm=\"BabyCam\", nonce=\""
                                + authenticationNonce + "\"\r\n", null);
                return true;
            }
            if ("DESCRIBE".equals(method)) {
                if (!request.uri.contains("/live")) {
                    sendResponse(request.cseq, 404, "Not Found", null, null);
                } else {
                    byte[] body = buildSdp().getBytes(StandardCharsets.US_ASCII);
                    sendResponse(request.cseq, 200, "OK",
                            "Content-Base: rtsp://" + getLocalIpv4Address() + ":" + port + "/live/\r\n"
                                    + "Content-Type: application/sdp\r\n", body);
                }
                return true;
            }
            if ("SETUP".equals(method)) {
                return handleSetup(request);
            }
            if ("PLAY".equals(method)) {
                if (!hasSession(request)) {
                    sendResponse(request.cseq, 454, "Session Not Found", null, null);
                    return true;
                }
                sendResponse(request.cseq, 200, "OK", "Range: npt=0.000-\r\n", null);
                playing = true;
                if (!countedPlaybackSession) {
                    countedPlaybackSession = true;
                    errorListener.onPlayingClientCountChanged(RtspServer.this,
                            playingClientCount.incrementAndGet());
                }
                List<byte[]> keyframe = lastVideoKeyframe;
                if (video != null && keyframe != null) {
                    sendVideo(keyframe, lastVideoKeyframePtsUs);
                }
                return true;
            }
            if ("PAUSE".equals(method)) {
                if (!hasSession(request)) {
                    sendResponse(request.cseq, 454, "Session Not Found", null, null);
                } else {
                    playing = false;
                    sendResponse(request.cseq, 200, "OK", null, null);
                }
                return true;
            }
            if ("GET_PARAMETER".equals(method) || "SET_PARAMETER".equals(method)) {
                sendResponse(request.cseq, 200, "OK", null, null);
                return true;
            }
            if ("TEARDOWN".equals(method)) {
                sendResponse(request.cseq, 200, "OK", null, null);
                return false;
            }
            sendResponse(request.cseq, 405, "Method Not Allowed", null, null);
            return true;
        }

        private boolean isAuthorized(Request request) {
            if (password.isEmpty()) {
                return true;
            }
            String authorization = request.headers.get("authorization");
            if (authorization == null) {
                return false;
            }
            if (authorization.regionMatches(true, 0, "Digest ", 0, 7)) {
                return isValidDigestAuthorization(request, authorization.substring(7));
            }
            return false;
        }

        private boolean isValidDigestAuthorization(Request request, String parameters) {
            Map<String, String> values = new HashMap<>();
            Matcher matcher = Pattern.compile("([A-Za-z]+)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^,\\s]+))")
                    .matcher(parameters);
            while (matcher.find()) {
                values.put(matcher.group(1).toLowerCase(Locale.US),
                        matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
            String username = values.get("username");
            String realm = values.get("realm");
            String nonce = values.get("nonce");
            String uri = values.get("uri");
            String actual = values.get("response");
            if (!RtspServer.this.username.equals(username) || !"BabyCam".equals(realm)
                    || !authenticationNonce.equals(nonce) || uri == null || actual == null
                    || !request.uri.equals(uri)) {
                return false;
            }
            String ha1 = md5Hex(username + ":BabyCam:" + password);
            String ha2 = md5Hex(request.method + ":" + uri);
            String expected = md5Hex(ha1 + ":" + authenticationNonce + ":" + ha2);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    actual.toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
        }

        private String md5Hex(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(digest.length * 2);
                for (byte item : digest) {
                    result.append(String.format(Locale.US, "%02x", item & 0xff));
                }
                return result.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("MD5 is unavailable", impossible);
            }
        }

        private boolean handleSetup(Request request) throws IOException {
            String transportHeader = request.headers.get("transport");
            if (transportHeader == null) {
                sendResponse(request.cseq, 400, "Bad Request", null, null);
                return true;
            }
            boolean isAudio = request.uri.toLowerCase(Locale.US).contains("trackid=1");
            TrackTransport old = isAudio ? audio : video;
            if (old != null) {
                old.close();
            }
            TrackTransport track;
            String lower = transportHeader.toLowerCase(Locale.US);
            if (lower.contains("interleaved") || lower.contains("/tcp")) {
                int[] channels = parsePair(transportHeader, "interleaved");
                if (channels == null) {
                    channels = isAudio ? new int[]{2, 3} : new int[]{0, 1};
                }
                if (!validPair(channels, 0, 255)) {
                    sendResponse(request.cseq, 461, "Unsupported Transport", null, null);
                    return true;
                }
                track = TrackTransport.tcp(isAudio, channels[0], channels[1]);
            } else {
                int[] ports = parsePair(transportHeader, "client_port");
                if (!validPair(ports, 1, 65_535)) {
                    sendResponse(request.cseq, 461, "Unsupported Transport", null, null);
                    return true;
                }
                track = TrackTransport.udp(isAudio, socket.getInetAddress(), ports[0], ports[1]);
            }
            if (isAudio) {
                audio = track;
            } else {
                video = track;
            }
            if (session == null) {
                session = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
            sendResponse(request.cseq, 200, "OK",
                    "Transport: " + track.responseTransport() + "\r\n", null);
            return true;
        }

        private boolean hasSession(Request request) {
            String requested = request.headers.get("session");
            return session != null && (requested == null
                    || requested.split(";", 2)[0].equals(session));
        }

        void sendVideo(List<byte[]> nals, long ptsUs) throws IOException {
            TrackTransport track = video;
            if (track == null) {
                return;
            }
            int timestamp = (int) ((ptsUs * 90L) / 1000L);
            synchronized (this) {
                for (int nalIndex = 0; nalIndex < nals.size(); nalIndex++) {
                    byte[] nal = nals.get(nalIndex);
                    if (nal.length == 0) {
                        continue;
                    }
                    boolean lastNal = nalIndex == nals.size() - 1;
                    if (nal.length <= MAX_RTP_PAYLOAD) {
                        sendRtp(track, VIDEO_PAYLOAD_TYPE, timestamp, nal, lastNal);
                    } else {
                        int offset = 1;
                        int maxFragment = MAX_RTP_PAYLOAD - 2;
                        while (offset < nal.length) {
                            int length = Math.min(maxFragment, nal.length - offset);
                            byte[] fragment = new byte[length + 2];
                            fragment[0] = (byte) ((nal[0] & 0xe0) | 28);
                            fragment[1] = (byte) (nal[0] & 0x1f);
                            if (offset == 1) {
                                fragment[1] |= (byte) 0x80;
                            }
                            boolean end = offset + length >= nal.length;
                            if (end) {
                                fragment[1] |= 0x40;
                            }
                            System.arraycopy(nal, offset, fragment, 2, length);
                            sendRtp(track, VIDEO_PAYLOAD_TYPE, timestamp, fragment, end && lastNal);
                            offset += length;
                        }
                    }
                }
            }
        }

        void sendAudio(byte[] aac, long ptsUs) throws IOException {
            TrackTransport track = audio;
            if (track == null) {
                return;
            }
            byte[] payload = new byte[aac.length + 4];
            payload[0] = 0;
            payload[1] = 16;
            // go2rtc's RFC 3640 depacketizer interprets AU-size as bytes
            // after removing the three reserved/index bits. Encode the
            // actual AAC frame length, not its bit length.
            int sizeInBytes = Math.min(0x1fff, aac.length);
            payload[2] = (byte) ((sizeInBytes >> 5) & 0xff);
            payload[3] = (byte) ((sizeInBytes & 0x1f) << 3);
            System.arraycopy(aac, 0, payload, 4, aac.length);
            int timestamp = (int) ((ptsUs * audioSampleRate) / 1_000_000L);
            synchronized (this) {
                sendRtp(track, AUDIO_PAYLOAD_TYPE, timestamp, payload, true);
            }
        }

        private void sendRtp(TrackTransport track, int payloadType, int timestamp,
                             byte[] payload, boolean marker) throws IOException {
            byte[] packet = new byte[payload.length + 12];
            packet[0] = (byte) 0x80;
            packet[1] = (byte) (payloadType | (marker ? 0x80 : 0));
            int sequence = track.sequence++ & 0xffff;
            packet[2] = (byte) (sequence >> 8);
            packet[3] = (byte) sequence;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;
            int ssrc = track.ssrc;
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            System.arraycopy(payload, 0, packet, 12, payload.length);
            if (!outbound.offer(new OutboundPacket(track, packet))) {
                throw new IOException("RTSP client is not consuming media");
            }
        }

        private void writeLoop() {
            try {
                while (alive) {
                    OutboundPacket next = outbound.take();
                    TrackTransport track = next.track;
                    if (track.tcp) {
                        synchronized (outputLock) {
                            output.write('$');
                            output.write(track.rtpChannel);
                            output.write(next.packet.length >> 8);
                            output.write(next.packet.length);
                            output.write(next.packet);
                            output.flush();
                        }
                    } else {
                        DatagramPacket datagram = new DatagramPacket(next.packet,
                                next.packet.length, track.clientAddress, track.clientRtpPort);
                        track.rtpSocket.send(datagram);
                    }
                }
            } catch (IOException ignored) {
                // Closing or a receiver that stopped consuming ends only this client.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        private Request readRequest(int firstByte) throws IOException {
            String requestLine = readLine(firstByte);
            if (requestLine == null || requestLine.isEmpty()) {
                return null;
            }
            String[] requestParts = requestLine.split("\\s+", 3);
            if (requestParts.length < 2) {
                return null;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            int headerCount = 0;
            while ((line = readLine(-1)) != null && !line.isEmpty()) {
                if (++headerCount > MAX_HEADER_COUNT) {
                    throw new IOException("Too many RTSP headers");
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
                throw new IOException("Invalid RTSP content length");
            }
            if (length < 0 || length > MAX_REQUEST_BODY) {
                throw new IOException("RTSP request body is too large");
            }
            if (length > 0) {
                readFully(new byte[length]);
            }
            return new Request(requestParts[0].toUpperCase(Locale.US), requestParts[1],
                    headers.get("cseq"), headers);
        }

        private String readLine(int firstByte) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (firstByte >= 0) {
                bytes.write(firstByte);
            }
            while (true) {
                int value = input.read();
                if (value < 0) {
                    return bytes.size() == 0 ? null
                            : bytes.toString(StandardCharsets.US_ASCII.name());
                }
                if (value == '\n') {
                    return bytes.toString(StandardCharsets.US_ASCII.name()).replaceAll("\\r$", "");
                }
                if (bytes.size() >= MAX_LINE_LENGTH) {
                    throw new IOException("RTSP line is too long");
                }
                bytes.write(value);
            }
        }

        private void skipInterleavedPacket() throws IOException {
            int channel = input.read();
            int high = input.read();
            int low = input.read();
            if (channel < 0 || high < 0 || low < 0) {
                throw new IOException("Incomplete interleaved packet");
            }
            int length = (high << 8) | low;
            byte[] ignored = new byte[length];
            readFully(ignored);
        }

        private void readFully(byte[] target) throws IOException {
            int offset = 0;
            while (offset < target.length) {
                int count = input.read(target, offset, target.length - offset);
                if (count < 0) {
                    throw new IOException("Unexpected end of stream");
                }
                offset += count;
            }
        }

        private void sendResponse(String cseq, int code, String reason,
                                  String extraHeaders, byte[] body) throws IOException {
            synchronized (outputLock) {
                if (!alive) return;
                StringBuilder response = new StringBuilder();
                response.append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n");
                if (cseq != null) response.append("CSeq: ").append(cseq).append("\r\n");
                if (session != null && code >= 200 && code < 300) {
                    response.append("Session: ").append(session).append("\r\n");
                }
                if (extraHeaders != null) response.append(extraHeaders);
                if (body != null) response.append("Content-Length: ")
                        .append(body.length).append("\r\n");
                response.append("\r\n");
                output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
                if (body != null) output.write(body);
                output.flush();
            }
        }

        @Override
        public synchronized void close() {
            if (!alive) {
                return;
            }
            alive = false;
            playing = false;
            boolean playbackSessionEnded = countedPlaybackSession;
            countedPlaybackSession = false;
            if (video != null) {
                video.close();
                video = null;
            }
            if (audio != null) {
                audio.close();
                audio = null;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            outbound.clear();
            writerThread.interrupt();
            clients.remove(this);
            if (playbackSessionEnded) {
                errorListener.onPlayingClientCountChanged(RtspServer.this,
                        Math.max(0, playingClientCount.decrementAndGet()));
            }
        }
    }

    private static final class OutboundPacket {
        final TrackTransport track;
        final byte[] packet;

        OutboundPacket(TrackTransport track, byte[] packet) {
            this.track = track;
            this.packet = packet;
        }
    }

    private static final class Request {
        final String method;
        final String uri;
        final String cseq;
        final Map<String, String> headers;

        Request(String method, String uri, String cseq, Map<String, String> headers) {
            this.method = method;
            this.uri = uri;
            this.cseq = cseq;
            this.headers = headers;
        }
    }

    private static final class TrackTransport implements Closeable {
        final boolean audio;
        final boolean tcp;
        final int rtpChannel;
        final int rtcpChannel;
        final InetAddress clientAddress;
        final int clientRtpPort;
        final int clientRtcpPort;
        final DatagramSocket rtpSocket;
        final DatagramSocket rtcpSocket;
        final int ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        int sequence = (int) (Math.random() * 65535);

        private TrackTransport(boolean audio, boolean tcp, int rtpChannel, int rtcpChannel,
                               InetAddress clientAddress, int clientRtpPort, int clientRtcpPort,
                               DatagramSocket rtpSocket, DatagramSocket rtcpSocket) {
            this.audio = audio;
            this.tcp = tcp;
            this.rtpChannel = rtpChannel;
            this.rtcpChannel = rtcpChannel;
            this.clientAddress = clientAddress;
            this.clientRtpPort = clientRtpPort;
            this.clientRtcpPort = clientRtcpPort;
            this.rtpSocket = rtpSocket;
            this.rtcpSocket = rtcpSocket;
        }

        static TrackTransport tcp(boolean audio, int rtpChannel, int rtcpChannel) {
            return new TrackTransport(audio, true, rtpChannel, rtcpChannel,
                    null, 0, 0, null, null);
        }

        static TrackTransport udp(boolean audio, InetAddress address, int rtpPort, int rtcpPort)
                throws SocketException {
            return new TrackTransport(audio, false, -1, -1, address, rtpPort, rtcpPort,
                    new DatagramSocket(), new DatagramSocket());
        }

        String responseTransport() {
            if (tcp) {
                return "RTP/AVP/TCP;unicast;interleaved=" + rtpChannel + "-" + rtcpChannel;
            }
            return "RTP/AVP;unicast;destination=" + clientAddress.getHostAddress()
                    + ";client_port=" + clientRtpPort + "-" + clientRtcpPort
                    + ";server_port=" + rtpSocket.getLocalPort() + "-" + rtcpSocket.getLocalPort();
        }

        @Override
        public void close() {
            if (rtpSocket != null) {
                rtpSocket.close();
            }
            if (rtcpSocket != null) {
                rtcpSocket.close();
            }
        }
    }
}
