package com.babycam;

/** Recalculate a changed alarm delay against the original outage, not the last retry. */
final class AlarmTiming {
    private AlarmTiming() { }
    static long remainingDelay(long outageStart, long now, int seconds) {
        return Math.max(0, Math.max(1, seconds) * 1000L - Math.max(0, now - outageStart));
    }
}
