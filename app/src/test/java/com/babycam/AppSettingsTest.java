package com.babycam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AppSettingsTest {
    @Test
    public void videoResolution_normalizesUnsupportedValues() {
        assertEquals(480, AppSettings.normalizeVideoResolution(480));
        assertEquals(720, AppSettings.normalizeVideoResolution(720));
        assertEquals(1080, AppSettings.normalizeVideoResolution(1080));
        assertEquals(720, AppSettings.normalizeVideoResolution(0));
        assertEquals(720, AppSettings.normalizeVideoResolution(2160));
    }

    @Test
    public void streamPort_acceptsOnlyReservedApplicationRange() {
        assertEquals(1024, AppSettings.normalizeStreamPort(1024));
        assertEquals(65533, AppSettings.normalizeStreamPort(65533));
        assertEquals(AppSettings.DEFAULT_STREAM_PORT, AppSettings.normalizeStreamPort(1023));
        assertEquals(AppSettings.DEFAULT_STREAM_PORT, AppSettings.normalizeStreamPort(65534));
    }
}
