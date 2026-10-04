package com.limelight;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.video.FrameTransferStats;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Drives a {@link BitrateCalibration}: launches one {@link Game} per step, collects each result
 * when the step's Game finishes and returns here, and shows the outcome.
 */
public class BitrateCalibrationActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_INTENT = "GameIntent";

    // Gives the previous connection time to shut down before the next one starts
    private static final int STEP_GAP_MS = 1500;
    // The bitrate setting's slider maximum
    private static final int MAX_BITRATE_KBPS = 300000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BitrateCalibration.Session session;
    private boolean resumed;
    private boolean launchWhenResumed;

    private TextView statusText;
    private TextView resultsText;
    private TextView recommendationText;
    private Button startButton;
    private Button applyButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bitrate_calibration);

        statusText = findViewById(R.id.calibrationStatus);
        resultsText = findViewById(R.id.calibrationResults);
        recommendationText = findViewById(R.id.calibrationRecommendation);
        startButton = findViewById(R.id.calibrationStartButton);
        applyButton = findViewById(R.id.calibrationApplyButton);
        Button closeButton = findViewById(R.id.calibrationCloseButton);

        // A fresh launch always starts over. A recreated activity (the system may destroy it
        // while a step's stream runs on top) picks up the session in progress.
        session = savedInstanceState != null ? BitrateCalibration.getSession() : null;
        if (session == null) {
            Intent gameIntent = getIntent().getParcelableExtra(EXTRA_GAME_INTENT);
            if (gameIntent == null) {
                finish();
                return;
            }
            PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(this);
            session = BitrateCalibration.startSession(gameIntent, prefConfig.fps, MAX_BITRATE_KBPS);
        }

        startButton.setOnClickListener(v -> launchNextStep());
        applyButton.setOnClickListener(v -> applyAndResume());
        closeButton.setOnClickListener(v -> close());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                close();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (session == null) {
            return;
        }

        if (session.stepRunning) {
            session.stepRunning = false;
            BitrateCalibration.Step step = BitrateCalibration.takePendingStep();
            if (step == null) {
                // The stream failed or the user left it before the measurement finished
                session.interrupted = true;
                session.done = true;
            } else if (session.addStep(step)) {
                handler.postDelayed(this::launchNextStep, STEP_GAP_MS);
            } else {
                session.done = true;
            }
        } else if (launchWhenResumed) {
            launchWhenResumed = false;
            launchNextStep();
        }

        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    private void launchNextStep() {
        if (session.done || session.stepRunning) {
            return;
        }
        if (!resumed) {
            // Android blocks activity launches from the background
            launchWhenResumed = true;
            return;
        }

        int kbps = session.nextKbps();
        Intent intent = new Intent(session.gameIntent);
        // Keep each step in this task, so finishing the stream returns here
        intent.setFlags(0);
        intent.putExtra(BitrateCalibration.EXTRA_STEP_BITRATE, kbps);
        session.stepRunning = true;
        startActivity(intent);
    }

    private void applyAndResume() {
        BitrateCalibration.Step recommendation = session.getRecommendation();
        if (recommendation == null) {
            return;
        }
        PreferenceConfiguration.saveBitrate(this, recommendation.requestedKbps);
        Toast.makeText(this, getString(R.string.calibration_applied, recommendation.requestedKbps / 1000),
                Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(session.gameIntent);
        intent.setFlags(0);
        close();
        startActivity(intent);
    }

    private void close() {
        handler.removeCallbacksAndMessages(null);
        BitrateCalibration.endSession();
        finish();
    }

    private void render() {
        boolean started = !session.steps.isEmpty() || session.done;

        startButton.setVisibility(started ? View.GONE : View.VISIBLE);
        if (!started) {
            statusText.setText(getString(R.string.calibration_intro, session.plannedKbps.size()));
        } else if (!session.done) {
            statusText.setText(getString(R.string.calibration_next_step,
                    session.nextKbps() / 1000, session.steps.size() + 1, session.plannedKbps.size()));
        } else {
            statusText.setText(R.string.calibration_done);
        }

        StringBuilder sb = new StringBuilder();
        for (BitrateCalibration.Step step : session.steps) {
            appendStep(sb, step);
        }
        resultsText.setText(sb.toString());
        resultsText.setVisibility(session.steps.isEmpty() ? View.GONE : View.VISIBLE);

        BitrateCalibration.Step recommendation = session.getRecommendation();
        applyButton.setVisibility(session.done && recommendation != null ? View.VISIBLE : View.GONE);
        if (recommendation != null) {
            applyButton.setText(getString(R.string.calibration_apply, recommendation.requestedKbps / 1000));
        }

        if (session.done) {
            recommendationText.setText(describeOutcome(recommendation));
            recommendationText.setVisibility(View.VISIBLE);
        } else {
            recommendationText.setVisibility(View.GONE);
        }
    }

    private void appendStep(StringBuilder sb, BitrateCalibration.Step step) {
        FrameTransferStats.Summary s = step.summary;
        sb.append(step.verdict == BitrateCalibration.Verdict.CLEAN ? "✓ " : step.isFailure() ? "✗ " : "• ");
        sb.append(getString(R.string.calibration_step_title, step.requestedKbps / 1000, verdictLabel(step.verdict)));
        sb.append('\n');
        sb.append(getString(R.string.calibration_step_details, s.getBitrateMbps(), s.avgTransferMs,
                s.worstWindowTransferMs, s.queuingDelayMs, s.getLossPercent()));
        sb.append('\n');
        if (s.keyframes > 0) {
            sb.append(getString(R.string.calibration_step_keyframes, s.avgKeyframeTransferMs)).append('\n');
        }
        if (!Double.isNaN(s.throughputMbps)) {
            sb.append(getString(R.string.calibration_step_link, s.throughputMbps)).append('\n');
        }
        sb.append('\n');
    }

    private String verdictLabel(BitrateCalibration.Verdict verdict) {
        switch (verdict) {
            case CLEAN:
                return getString(R.string.calibration_verdict_clean);
            case UNDERLOADED:
                return getString(R.string.calibration_verdict_underloaded);
            case NO_DATA:
                return getString(R.string.calibration_verdict_no_data);
            case LOSS:
                return getString(R.string.calibration_verdict_loss);
            case SLOW_TRANSFER:
                return getString(R.string.calibration_verdict_slow);
            case QUEUING:
                return getString(R.string.calibration_verdict_queuing);
            default:
                return "";
        }
    }

    private String describeOutcome(BitrateCalibration.Step recommendation) {
        StringBuilder sb = new StringBuilder();
        BitrateCalibration.Step failure = session.getFirstFailure();

        if (recommendation != null) {
            sb.append(getString(R.string.calibration_recommended, recommendation.requestedKbps / 1000,
                    recommendation.summary.avgTransferMs));
        } else if (failure != null && failure == session.steps.get(0)) {
            sb.append(getString(R.string.calibration_none_first_failed, failure.requestedKbps / 1000));
        } else {
            sb.append(getString(R.string.calibration_none));
        }

        if (failure != null && failure.verdict == BitrateCalibration.Verdict.NO_DATA) {
            sb.append("\n\n").append(getString(R.string.calibration_reason_no_data, failure.requestedKbps / 1000));
        } else if (failure != null) {
            sb.append("\n\n").append(getString(R.string.calibration_reason_failure,
                    failure.requestedKbps / 1000, verdictLabel(failure.verdict)));
        } else if (session.stoppedForLowLoad) {
            sb.append("\n\n").append(getString(R.string.calibration_reason_low_load));
        } else if (session.interrupted) {
            sb.append("\n\n").append(getString(R.string.calibration_reason_interrupted));
        } else {
            sb.append("\n\n").append(getString(R.string.calibration_reason_all_clean));
        }
        return sb.toString();
    }
}
