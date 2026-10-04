package com.limelight.binding.video;

/**
 * Network-side timing of received video frames.
 * <p>
 * For each frame, common-c reports when its first packet arrived and when the frame was fully
 * reassembled. The host sends every frame as a burst well above Wi-Fi speeds, so the time
 * between those two points is how long the link took to carry the frame. From that we derive:
 * <ul>
 *     <li>transfer time: the delay the current bitrate adds to every frame</li>
 *     <li>link throughput: payload bytes divided by transfer time, over large frames only</li>
 *     <li>queuing delay: how much later than the fastest frame each frame's first packet arrived,
 *     relative to the host's capture timestamp. This grows when the access point's buffer
 *     stops draining between frames, which is the sign of a saturated link.</li>
 * </ul>
 * Timestamps are whole milliseconds, so every value is an average over many frames.
 * Dividing sums (rather than averaging per-frame ratios) keeps that rounding unbiased.
 */
public class FrameTransferStats {
    // Frames smaller than this can arrive within a single Wi-Fi aggregate, which makes their
    // transfer time say nothing about link speed.
    private static final int MIN_THROUGHPUT_FRAME_BYTES = 64 * 1024;
    private static final int WINDOW_MS = 1000;
    private static final int BASELINE_WINDOWS = 10;

    public static class Summary {
        public int frames;
        public int framesLost;
        public long bytes;
        public long elapsedMs;

        public double avgTransferMs;
        // Highest one-second average transfer time in the period
        public double worstWindowTransferMs;

        public int keyframes;
        public double avgKeyframeTransferMs;

        // NaN when there weren't enough large frames to tell
        public double throughputMbps = Double.NaN;

        public double queuingDelayMs;

        public double getBitrateMbps() {
            return elapsedMs > 0 ? bytes * 8.0 / elapsedMs / 1000.0 : 0;
        }

        public double getLossPercent() {
            int total = frames + framesLost;
            return total > 0 ? 100.0 * framesLost / total : 0;
        }
    }

    private static class Accumulator {
        int frames;
        int framesLost;
        long bytes;
        long transferMs;
        int keyframes;
        long keyframeTransferMs;
        long throughputBytes;
        long throughputMs;
        double delaySum;
        int delayFrames;
        double delayMin = Double.MAX_VALUE;
        long firstReceiveMs;
        long lastEnqueueMs;

        void clear() {
            frames = framesLost = keyframes = delayFrames = 0;
            bytes = transferMs = keyframeTransferMs = throughputBytes = throughputMs = 0;
            delaySum = 0;
            delayMin = Double.MAX_VALUE;
            firstReceiveMs = lastEnqueueMs = 0;
        }

        Summary summarize(double delayBaseline) {
            Summary s = new Summary();
            s.frames = frames;
            s.framesLost = framesLost;
            s.bytes = bytes;
            s.elapsedMs = lastEnqueueMs - firstReceiveMs;
            s.avgTransferMs = frames > 0 ? (double) transferMs / frames : 0;
            s.worstWindowTransferMs = s.avgTransferMs;
            s.keyframes = keyframes;
            s.avgKeyframeTransferMs = keyframes > 0 ? (double) keyframeTransferMs / keyframes : 0;
            // Require a few milliseconds in total so the rounding averages out
            if (throughputMs >= 20) {
                s.throughputMbps = throughputBytes * 8.0 / throughputMs / 1000.0;
            }
            if (delayFrames > 0) {
                s.queuingDelayMs = Math.max(0, delaySum / delayFrames - delayBaseline);
            }
            return s;
        }
    }

    private final Accumulator window = new Accumulator();
    private final Accumulator period = new Accumulator();
    private double periodWorstWindowTransferMs;
    private final double[] recentWindowDelayMins = new double[BASELINE_WINDOWS];
    private int recentWindowCount;
    private Summary lastWindow;

    /**
     * Records one fully received frame.
     *
     * @param hostProcessingLatency host capture-to-send time in 1/10 ms, or 0 if unknown
     */
    public synchronized void onFrame(int bytes, boolean keyframe, long receiveTimeMs, long enqueueTimeMs,
                                     int presentationTimeMs, char hostProcessingLatency) {
        if (window.frames > 0 && receiveTimeMs - window.firstReceiveMs >= WINDOW_MS) {
            flipWindow();
        }

        long transferMs = Math.max(0, enqueueTimeMs - receiveTimeMs);
        // Both clocks are fixed for the whole connection, so this is the one-way delay plus a
        // constant offset. Only differences between frames are meaningful.
        double delay = receiveTimeMs - presentationTimeMs - hostProcessingLatency / 10.0;

        add(window, bytes, keyframe, receiveTimeMs, enqueueTimeMs, transferMs, delay);
        add(period, bytes, keyframe, receiveTimeMs, enqueueTimeMs, transferMs, delay);
    }

    public synchronized void onFramesLost(int count) {
        window.framesLost += count;
        period.framesLost += count;
    }

    /** Starts a new measurement period, e.g. once a calibration step has warmed up. */
    public synchronized void resetPeriod() {
        period.clear();
        periodWorstWindowTransferMs = 0;
    }

    /** Totals since the last {@link #resetPeriod()}, or since the stream started. */
    public synchronized Summary getPeriodSummary() {
        Summary s = period.summarize(period.delayMin);
        s.worstWindowTransferMs = Math.max(periodWorstWindowTransferMs, s.avgTransferMs);
        return s;
    }

    /** The most recent complete one-second window, or null before the first one. */
    public synchronized Summary getLastWindowSummary() {
        return lastWindow;
    }

    private static void add(Accumulator a, int bytes, boolean keyframe, long receiveTimeMs, long enqueueTimeMs,
                            long transferMs, double delay) {
        if (a.frames == 0) {
            a.firstReceiveMs = receiveTimeMs;
        }
        a.lastEnqueueMs = Math.max(a.lastEnqueueMs, enqueueTimeMs);
        a.frames++;
        a.bytes += bytes;
        a.transferMs += transferMs;
        if (keyframe) {
            a.keyframes++;
            a.keyframeTransferMs += transferMs;
        }
        if (bytes >= MIN_THROUGHPUT_FRAME_BYTES) {
            a.throughputBytes += bytes;
            a.throughputMs += transferMs;
        }
        a.delaySum += delay;
        a.delayFrames++;
        a.delayMin = Math.min(a.delayMin, delay);
    }

    private void flipWindow() {
        // Queuing in a live stream is measured against the fastest frame of the last few
        // seconds, so a long session doesn't anchor on one lucky frame from minutes ago.
        recentWindowDelayMins[recentWindowCount % BASELINE_WINDOWS] = window.delayMin;
        recentWindowCount++;
        double baseline = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(recentWindowCount, BASELINE_WINDOWS); i++) {
            baseline = Math.min(baseline, recentWindowDelayMins[i]);
        }

        lastWindow = window.summarize(baseline);
        periodWorstWindowTransferMs = Math.max(periodWorstWindowTransferMs, lastWindow.avgTransferMs);
        window.clear();
    }
}
