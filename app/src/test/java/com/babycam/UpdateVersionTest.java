package com.babycam;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateVersionTest {
    @Test public void comparesNumericComponents() {
        assertTrue(UpdateVersion.isNewer("v1.10", "1.9.9"));
        assertTrue(UpdateVersion.isNewer("v2.0.0", "1.99"));
        assertFalse(UpdateVersion.isNewer("v1.0", "1.0.0"));
        assertFalse(UpdateVersion.isNewer("v1.0", "1.0.1"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsPrerelease() { UpdateVersion.isNewer("v2.0-beta", "1.0"); }
}
