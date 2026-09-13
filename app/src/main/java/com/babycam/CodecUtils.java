package com.babycam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class CodecUtils {
    private CodecUtils() {
    }

    static MediaCodec createHardwareEncoder(String mime, MediaFormat format) throws IOException {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        IOException lastError = null;
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder() || !supports(info, mime) || isSoftware(info)) {
                continue;
            }
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createByCodecName(info.getName());
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                return codec;
            } catch (Exception e) {
                releaseFailedCodec(codec);
                lastError = new IOException("Could not configure " + info.getName(), e);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("No hardware encoder found for " + mime);
    }

    static MediaCodec createAnyEncoder(String mime, MediaFormat format) throws IOException {
        try {
            return createHardwareEncoder(mime, format);
        } catch (IOException hardwareError) {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            IOException lastError = hardwareError;
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder() || !supports(info, mime) || !isSoftware(info)) {
                    continue;
                }
                MediaCodec codec = null;
                try {
                    codec = MediaCodec.createByCodecName(info.getName());
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    return codec;
                } catch (Exception error) {
                    releaseFailedCodec(codec);
                    lastError = new IOException("Could not configure " + info.getName(), error);
                }
            }
            throw lastError;
        }
    }

    private static void releaseFailedCodec(MediaCodec codec) {
        if (codec != null) {
            try { codec.release(); } catch (RuntimeException ignored) { }
        }
    }

    private static boolean supports(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (mime.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                if (info.isSoftwareOnly()) {
                    return true;
                }
                if (info.isHardwareAccelerated()) {
                    return false;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the codec-name check for vendor-specific implementations.
            }
        }
        String name = info.getName().toLowerCase(Locale.US);
        return name.startsWith("omx.google.")
                || name.startsWith("c2.android.")
                || name.contains("software")
                || name.contains("sw.");
    }

    static byte[] copyBuffer(ByteBuffer source, int offset, int size) {
        ByteBuffer copy = source.duplicate();
        copy.position(offset);
        copy.limit(offset + size);
        byte[] result = new byte[size];
        copy.get(result);
        return result;
    }

    static List<byte[]> extractNals(byte[] data) {
        if (data == null || data.length == 0) {
            return Collections.emptyList();
        }
        int firstStart = findStartCode(data, 0);
        if (firstStart >= 0) {
            ArrayList<byte[]> result = new ArrayList<>();
            int cursor = firstStart;
            while (cursor >= 0 && cursor < data.length) {
                int prefixLength = startCodeLength(data, cursor);
                int nalStart = cursor + prefixLength;
                int next = findStartCode(data, nalStart);
                int nalEnd = next >= 0 ? next : data.length;
                if (nalEnd > nalStart) {
                    result.add(Arrays.copyOfRange(data, nalStart, nalEnd));
                }
                cursor = next;
            }
            return result;
        }

        // Some AVC encoders expose an AVCC access unit (4-byte NAL lengths).
        if (data.length >= 4) {
            ArrayList<byte[]> lengthPrefixed = new ArrayList<>();
            int offset = 0;
            boolean valid = true;
            while (offset + 4 <= data.length) {
                int size = ((data[offset] & 0xff) << 24)
                        | ((data[offset + 1] & 0xff) << 16)
                        | ((data[offset + 2] & 0xff) << 8)
                        | (data[offset + 3] & 0xff);
                offset += 4;
                if (size <= 0 || offset + size > data.length) {
                    valid = false;
                    break;
                }
                lengthPrefixed.add(Arrays.copyOfRange(data, offset, offset + size));
                offset += size;
            }
            if (valid && offset == data.length && !lengthPrefixed.isEmpty()) {
                return lengthPrefixed;
            }
        }
        return Collections.singletonList(data);
    }

    static List<byte[]> extractCodecNals(MediaFormat format) {
        ArrayList<byte[]> result = new ArrayList<>();
        addCodecBuffer(result, format, "csd-0");
        addCodecBuffer(result, format, "csd-1");
        return result;
    }

    private static void addCodecBuffer(List<byte[]> result, MediaFormat format, String key) {
        ByteBuffer buffer = format.getByteBuffer(key);
        if (buffer == null) {
            return;
        }
        byte[] bytes = copyBuffer(buffer, buffer.position(), buffer.remaining());
        result.addAll(extractNals(bytes));
    }

    static byte[] findNalType(List<byte[]> nals, int type) {
        for (byte[] nal : nals) {
            if (nal.length > 0 && (nal[0] & 0x1f) == type) {
                return nal;
            }
        }
        return null;
    }

    static String base64(byte[] data) {
        return data == null ? "" : Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
            if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int at) {
        return at + 3 < data.length && data[at] == 0 && data[at + 1] == 0
                && data[at + 2] == 0 && data[at + 3] == 1 ? 4 : 3;
    }

    static byte[] removeAdtsHeader(byte[] data) {
        if (data.length >= 7 && (data[0] & 0xff) == 0xff && (data[1] & 0xf6) == 0xf0) {
            int headerLength = (data[1] & 1) == 0 ? 9 : 7;
            if (data.length > headerLength) {
                return Arrays.copyOfRange(data, headerLength, data.length);
            }
        }
        return data;
    }
}
