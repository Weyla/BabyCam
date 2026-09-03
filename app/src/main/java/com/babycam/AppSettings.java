package com.babycam;

import android.content.Context;
import android.content.SharedPreferences;

/** Single source of truth for user-configurable, device-local settings. */
final class AppSettings {
    static final String PREFS = "babycam_settings";
    static final String KEY_STREAM_PASSWORD = "stream_password";
    static final String KEY_STREAM_USERNAME = "stream_username";
    static final String KEY_STREAM_PORT = "stream_port";
    static final String KEY_ARMED_ENABLED = "armed_enabled";
    static final String KEY_LAST_VIDEO_MODE = "last_video_mode";
    static final String KEY_VIDEO_RESOLUTION = "video_resolution";
    static final String KEY_LOW_LATENCY = "low_latency";
    static final String KEY_RECEIVER_HOST = "receiver_host";
    static final String KEY_RECEIVER_PASSWORD = "receiver_password";
    static final String KEY_RECEIVER_USERNAME = "receiver_username";
    static final String KEY_LISTEN_ONLY = "listen_only";
    static final String KEY_ALARM_ENABLED = "alarm_enabled";
    static final String KEY_ALARM_DELAY_SECONDS = "alarm_delay_seconds";
    static final String KEY_ALARM_VOLUME = "alarm_volume";
    static final String KEY_ALARM_SOUND_URI = "alarm_sound_uri";

    static final int DEFAULT_ALARM_DELAY_SECONDS = 15;
    static final int DEFAULT_ALARM_VOLUME = 80;
    static final int DEFAULT_STREAM_PORT = 8554;
    static final int MIN_STREAM_PORT = 1024;
    static final int MAX_STREAM_PORT = 65533;
    static final int DEFAULT_VIDEO_RESOLUTION = 720;
    static final String DEFAULT_USERNAME = "babycam";

    private AppSettings() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int normalizeVideoResolution(int resolution) {
        return resolution == 480 || resolution == 1080 ? resolution : DEFAULT_VIDEO_RESOLUTION;
    }

    static int normalizeStreamPort(int port) {
        return port >= MIN_STREAM_PORT && port <= MAX_STREAM_PORT
                ? port : DEFAULT_STREAM_PORT;
    }
}
