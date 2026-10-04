package com.babycam;

import org.junit.Test;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RtpMediaQueueTest {
    @Test public void largeFragmentedKeyframeIsOneCompleteQueueEntry() throws Exception {
        RtpMediaQueue<String> queue = new RtpMediaQueue<>();
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 300; i++) packets.add(new byte[1212]);
        assertTrue(queue.offerVideo("video", packets, true, 0));
        assertSame(packets, queue.take().packets);
        queue.close();
        assertNull(queue.take());
    }

    @Test public void congestionKeepsAudioAndRequiresAKeyframeToResumeVideo() throws Exception {
        RtpMediaQueue<String> queue = new RtpMediaQueue<>();
        byte[] audio = new byte[100];
        queue.offerAudio("audio", audio, 0);
        assertTrue(queue.offerVideo("video", packet(1_000_000), true, 0));
        assertTrue(queue.offerVideo("video", packet(1_000_000), false, 0));
        assertFalse(queue.offerVideo("video", packet(1_000_000), false, 0));
        assertFalse(queue.offerVideo("video", packet(10), false, 0));
        assertTrue(queue.offerVideo("video", packet(100), true, 0));
        assertSame(audio, queue.take().packets.get(0));
        assertEquals(100, queue.take().bytes);
        queue.close();
        assertNull(queue.take());
    }

    @Test public void expiredVideoDropsDependentFramesButKeepsFreshAudio() throws Exception {
        RtpMediaQueue<String> queue = new RtpMediaQueue<>();
        queue.offerVideo("video", packet(100), true, 0);
        queue.offerVideo("video", packet(100), false, RtpMediaQueue.MAX_AGE_NS / 2);
        byte[] liveAudio = new byte[30];
        queue.offerAudio("audio", liveAudio, RtpMediaQueue.MAX_AGE_NS + 1);
        assertFalse(queue.offerVideo("video", packet(10), false, RtpMediaQueue.MAX_AGE_NS + 2));
        assertSame(liveAudio, queue.take().packets.get(0));
        assertTrue(queue.offerVideo("video", packet(20), true, RtpMediaQueue.MAX_AGE_NS + 3));
        assertEquals(20, queue.take().bytes);
    }

    @Test public void oversizedFrameIsDroppedWithoutBlockingAudio() throws Exception {
        RtpMediaQueue<String> queue = new RtpMediaQueue<>();
        assertFalse(queue.offerVideo("video", packet(RtpMediaQueue.MAX_BYTES + 1), true, 0));
        queue.offerAudio("audio", new byte[5], 0);
        assertFalse(queue.take().video);
        assertTrue(queue.offerVideo("video", packet(100), true, 0));
        assertTrue(queue.take().video);
    }

    @Test public void audioBacklogIsBoundedAndKeepsTheNewestFrames() throws Exception {
        RtpMediaQueue<Integer> queue = new RtpMediaQueue<>();
        for (int i = 0; i < 200; i++) queue.offerAudio(i, new byte[10], 0);
        assertEquals(Integer.valueOf(200 - RtpMediaQueue.MAX_FRAMES), queue.take().transport);
        queue.close();
        assertNull(queue.take());
    }

    @Test public void pauseClearsQueuedMediaAndResumeStartsOnAKeyframe() throws Exception {
        RtpMediaQueue<String> queue = new RtpMediaQueue<>();
        queue.offerVideo("video", packet(100), true, 0);
        queue.offerAudio("audio", new byte[5], 0);
        queue.clear();
        assertFalse(queue.offerVideo("video", packet(50), false, 0));
        assertTrue(queue.offerVideo("video", packet(25), true, 0));
        assertEquals(25, queue.take().bytes);
        queue.close();
        assertNull(queue.take());
    }

    private static List<byte[]> packet(int size) { return Collections.singletonList(new byte[size]); }
}
