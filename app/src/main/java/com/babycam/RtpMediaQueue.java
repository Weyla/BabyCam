package com.babycam;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/** Bounded live-media queue. Video is admitted/dropped only as complete access units. */
final class RtpMediaQueue<T> {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_FRAMES = 96;
    static final long MAX_AGE_NS = 750_000_000L;

    static final class Frame<T> {
        final T transport;
        final List<byte[]> packets;
        final boolean video;
        final int bytes;
        final long queuedAtNs;

        Frame(T transport, List<byte[]> packets, boolean video, long nowNs) {
            this.transport = transport;
            this.packets = packets;
            this.video = video;
            this.queuedAtNs = nowNs;
            int size = 0;
            for (byte[] packet : packets) size += packet.length;
            bytes = size;
        }
    }

    private final ArrayDeque<Frame<T>> frames = new ArrayDeque<>();
    private int bytes;
    private boolean waitingForKeyframe = true;
    private boolean closed;

    synchronized boolean offerVideo(T transport, List<byte[]> packets, boolean keyframe, long nowNs) {
        if (closed) return false;
        expire(nowNs);
        Frame<T> frame = new Frame<>(transport, packets, true, nowNs);
        if (frame.bytes > MAX_BYTES || full(frame.bytes)) discardVideo();
        if (frame.bytes > MAX_BYTES || full(frame.bytes) || waitingForKeyframe && !keyframe) {
            return false;
        }
        add(frame);
        if (keyframe) waitingForKeyframe = false;
        return true;
    }

    synchronized void offerAudio(T transport, byte[] packet, long nowNs) {
        if (closed) return;
        expire(nowNs);
        Frame<T> frame = new Frame<>(transport, java.util.Collections.singletonList(packet), false, nowNs);
        if (full(frame.bytes)) discardVideo();
        // Audio congestion is also bounded: keep live audio rather than replaying a backlog.
        while (!frames.isEmpty() && full(frame.bytes)) removeFirst();
        if (frame.bytes <= MAX_BYTES) add(frame);
    }

    synchronized Frame<T> take() throws InterruptedException {
        while (frames.isEmpty() && !closed) wait();
        if (closed) return null;
        Frame<T> frame = frames.removeFirst();
        bytes -= frame.bytes;
        return frame;
    }

    synchronized void clear() {
        frames.clear();
        bytes = 0;
        waitingForKeyframe = true;
    }

    synchronized void close() {
        closed = true;
        clear();
        notifyAll();
    }

    private boolean full(int additionalBytes) {
        return frames.size() >= MAX_FRAMES || bytes + additionalBytes > MAX_BYTES;
    }

    private void add(Frame<T> frame) {
        frames.addLast(frame);
        bytes += frame.bytes;
        notifyAll();
    }

    private void expire(long nowNs) {
        boolean expiredVideo = false;
        while (!frames.isEmpty() && nowNs - frames.peekFirst().queuedAtNs > MAX_AGE_NS) {
            expiredVideo |= frames.peekFirst().video;
            removeFirst();
        }
        if (expiredVideo) discardVideo();
    }

    private void removeFirst() {
        bytes -= frames.removeFirst().bytes;
    }

    private void discardVideo() {
        Iterator<Frame<T>> iterator = frames.iterator();
        while (iterator.hasNext()) {
            Frame<T> frame = iterator.next();
            if (frame.video) {
                bytes -= frame.bytes;
                iterator.remove();
            }
        }
        waitingForKeyframe = true;
    }
}
