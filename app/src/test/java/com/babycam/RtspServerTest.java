package com.babycam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RtspServerTest {
    @Test
    public void privateIpv4Literal_acceptsRfc1918Ranges() {
        assertTrue(RtspServer.isPrivateIpv4Literal("10.0.0.1"));
        assertTrue(RtspServer.isPrivateIpv4Literal("172.16.0.1"));
        assertTrue(RtspServer.isPrivateIpv4Literal("172.31.255.254"));
        assertTrue(RtspServer.isPrivateIpv4Literal("192.168.1.20"));
    }

    @Test
    public void privateIpv4Literal_rejectsNonPrivateAndMalformedAddresses() {
        assertFalse(RtspServer.isPrivateIpv4Literal(null));
        assertFalse(RtspServer.isPrivateIpv4Literal(""));
        assertFalse(RtspServer.isPrivateIpv4Literal("127.0.0.1"));
        assertFalse(RtspServer.isPrivateIpv4Literal("169.254.1.1"));
        assertFalse(RtspServer.isPrivateIpv4Literal("172.15.0.1"));
        assertFalse(RtspServer.isPrivateIpv4Literal("172.32.0.1"));
        assertFalse(RtspServer.isPrivateIpv4Literal("8.8.8.8"));
        assertFalse(RtspServer.isPrivateIpv4Literal("192.168.1"));
        assertFalse(RtspServer.isPrivateIpv4Literal("192.168.1.256"));
        assertFalse(RtspServer.isPrivateIpv4Literal("camera.local"));
        assertFalse(RtspServer.isPrivateIpv4Literal("2001:db8::1"));
    }
}
