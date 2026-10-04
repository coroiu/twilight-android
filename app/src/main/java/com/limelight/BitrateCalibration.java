package com.limelight;

import android.content.Intent;

import com.limelight.binding.video.FrameTransferStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Bitrate calibration: restarts the stream at increasing bitrates, measures each one and
 * recommends the highest bitrate the network carried without trouble.
 * <p>
 * Each step is a normal {@link Game} launch that resumes the running app with a bitrate
 * override. The state lives here, in a static, because steps run in separate Game
 * activities while {@link BitrateCalibrationActivity} waits underneath.
 */
public class BitrateCalibration {
    public static final String EXTRA_STEP_BITRATE = "CalibrationStepBitrateKbps";

    static final int WARMUP_MS = 3000;
    static final int MEASURE_MS = 10000;

    private static final int[] STEPS_KBPS = {10000, 20000, 30000, 50000, 75000, 100000, 150000, 200000, 250000, 300000};

    // Network drops above this mean the link is losing packets faster than FEC can recover
    private static final double MAX_LOSS_PERCENT = 1.0;
    // Transfer time as a share of the frame interval. Above half, the access point's queue
    // barely empties before the next frame arrives.
    private static final double MAX_AVG_TRANSFER_SHARE = 0.5;
    private static final double MAX_WORST_WINDOW_TRANSFER_SHARE = 1.0;
    // Queuing delay growth over the first step that counts as a filling buffer
    private static final double MAX_QUEUING_GROWTH_MS = 3.0;
    // The host spends about 80% of the requested bitrate on video (the rest is FEC and audio).
    // Far below that, the picture isn't complex enough to fill the bitrate, or the host caps it.
    private static final double MIN_LOAD_SHARE = 0.5;
    private static final int MAX_UNDERLOADED_IN_A_ROW = 2;

    enum Verdict {
        CLEAN,
        UNDERLOADED,
        NO_DATA,
        LOSS,
        SLOW_TRANSFER,
        QUEUING,
    }

    static class Step {
        final int requestedKbps;
        final FrameTransferStats.Summary summary;
        Verdict verdict;

        Step(int requestedKbps, FrameTransferStats.Summary summary) {
            this.requestedKbps = requestedKbps;
            this.summary = summary;
        }

        boolean isFailure() {
            return verdict == Verdict.LOSS || verdict == Verdict.SLOW_TRANSFER ||
                    verdict == Verdict.QUEUING || verdict == Verdict.NO_DATA;
        }
    }

    static class Session {
        final Intent gameIntent;
        final float fps;
        final List<Integer> plannedKbps = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        boolean stepRunning;
        boolean done;
        // Set when the run ended early for a reason other than a failed step
        boolean interrupted;
        boolean stoppedForLowLoad;

        Session(Intent gameIntent, float fps, int maxKbps) {
            this.gameIntent = gameIntent;
            this.fps = fps;
            for (int kbps : STEPS_KBPS) {
                if (kbps <= maxKbps) {
                    plannedKbps.add(kbps);
                }
            }
        }

        int nextKbps() {
            return plannedKbps.get(steps.size());
        }

        double frameIntervalMs() {
            return 1000.0 / fps;
        }

        /**
         * Grades a finished step and decides whether to continue.
         *
         * @return true if another step should run
         */
        boolean addStep(Step step) {
            FrameTransferStats.Summary s = step.summary;
            double interval = frameIntervalMs();

            if (s.frames < fps * MEASURE_MS / 1000 / 4) {
                step.verdict = Verdict.NO_DATA;
            } else if (s.getLossPercent() > MAX_LOSS_PERCENT) {
                step.verdict = Verdict.LOSS;
            } else if (s.avgTransferMs > interval * MAX_AVG_TRANSFER_SHARE ||
                    s.worstWindowTransferMs > interval * MAX_WORST_WINDOW_TRANSFER_SHARE) {
                step.verdict = Verdict.SLOW_TRANSFER;
            } else if (!steps.isEmpty() &&
                    s.queuingDelayMs > steps.get(0).summary.queuingDelayMs + MAX_QUEUING_GROWTH_MS) {
                step.verdict = Verdict.QUEUING;
            } else if (s.getBitrateMbps() * 1000 < step.requestedKbps * MIN_LOAD_SHARE) {
                step.verdict = Verdict.UNDERLOADED;
            } else {
                step.verdict = Verdict.CLEAN;
            }
            steps.add(step);

            if (step.isFailure() || steps.size() >= plannedKbps.size()) {
                return false;
            }

            int underloadedInARow = 0;
            for (int i = steps.size() - 1; i >= 0 && steps.get(i).verdict == Verdict.UNDERLOADED; i--) {
                underloadedInARow++;
            }
            if (underloadedInARow >= MAX_UNDERLOADED_IN_A_ROW) {
                stoppedForLowLoad = true;
                return false;
            }
            return true;
        }

        /** The highest step that was clean and actually used its bitrate, or null. */
        Step getRecommendation() {
            Step best = null;
            for (Step step : steps) {
                if (step.verdict == Verdict.CLEAN) {
                    best = step;
                } else if (step.isFailure()) {
                    break;
                }
            }
            return best;
        }

        Step getFirstFailure() {
            for (Step step : steps) {
                if (step.isFailure()) {
                    return step;
                }
            }
            return null;
        }
    }

    private static Session session;

    static synchronized Session getSession() {
        return session;
    }

    static synchronized Session startSession(Intent gameIntent, float fps, int maxKbps) {
        session = new Session(gameIntent, fps, maxKbps);
        return session;
    }

    static synchronized void endSession() {
        session = null;
    }

    private static Step pendingStep;

    /** Called by {@link Game} when a step's measurement completes. */
    static synchronized void reportStep(int requestedKbps, FrameTransferStats.Summary summary) {
        if (session != null && session.stepRunning) {
            pendingStep = new Step(requestedKbps, summary);
        }
    }

    /** Returns and clears the result of the step that just ended, or null if it never finished. */
    static synchronized Step takePendingStep() {
        Step step = pendingStep;
        pendingStep = null;
        return step;
    }
}
