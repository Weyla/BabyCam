package com.babycam;

import org.junit.Test;
import static org.junit.Assert.*;

public class MonitoringPolicyTest {
    @Test public void changingDelayUsesOriginalOutageTime() {
        assertEquals(5000, AlarmTiming.remainingDelay(1000, 11000, 15));
        assertEquals(0, AlarmTiming.remainingDelay(1000, 11000, 5));
        assertEquals(20000, AlarmTiming.remainingDelay(1000, 11000, 30));
    }
    @Test public void everyEndpointChangeInvalidatesListenerReuse() {
        ControlConfiguration old = new ControlConfiguration("192.168.1.2", 8555, "cam", "old");
        assertEquals(old, new ControlConfiguration("192.168.1.2", 8555, "cam", "old"));
        assertNotEquals(old, new ControlConfiguration("192.168.1.2", 8555, "cam", "new"));
        assertNotEquals(old, new ControlConfiguration("192.168.1.2", 8555, "other", "old"));
        assertNotEquals(old, new ControlConfiguration("192.168.1.2", 9001, "cam", "old"));
        assertNotEquals(old, new ControlConfiguration("192.168.1.3", 8555, "cam", "old"));
    }
}
