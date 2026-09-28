package com.limelight.binding.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Captures the local microphone and sends it to the host in 20 ms Opus frames.
 *
 * Capture never starts Bluetooth SCO, so Bluetooth headphones stay in their
 * high quality A2DP mode and the phone's own microphone is used instead.
 */
public class MicrophoneStream {
    private static final int SAMPLE_RATE = 48000;
    // Must match MIC_FRAME_SAMPLES in simplejni.c
    private static final int FRAME_SAMPLES = 960;

    private final Context context;
    private Thread captureThread;
    private volatile boolean running;

    public MicrophoneStream(Context context) {
        this.context = context;
    }

    public synchronized boolean isRunning() {
        return captureThread != null;
    }

    public synchronized void start() {
        if (captureThread != null) {
            return;
        }

        running = true;
        captureThread = new Thread(this::captureLoop, "Microphone");
        captureThread.start();
    }

    public synchronized void stop() {
        if (captureThread == null) {
            return;
        }

        running = false;
        try {
            // A read returns within one frame, so this is quick
            captureThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        captureThread = null;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder;
        try {
            // VOICE_COMMUNICATION enables the platform echo canceller, which matters when
            // game audio plays from the phone speaker. It does not start SCO by itself.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferSize, FRAME_SAMPLES * 2 * 4));
        } catch (IllegalArgumentException | SecurityException e) {
            LimeLog.warning("Microphone: couldn't create AudioRecord: " + e);
            return;
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            LimeLog.warning("Microphone: AudioRecord failed to initialize");
            recorder.release();
            return;
        }

        preferBuiltInMicOverBluetooth(recorder);

        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(recorder.getAudioSessionId());
            if (echoCanceler != null) {
                echoCanceler.setEnabled(true);
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
            if (noiseSuppressor != null) {
                noiseSuppressor.setEnabled(true);
            }
        }

        short[] frame = new short[FRAME_SAMPLES];
        try {
            recorder.startRecording();
            LimeLog.info("Microphone: capture started");

            while (running) {
                int filled = 0;
                while (filled < FRAME_SAMPLES && running) {
                    int read = recorder.read(frame, filled, FRAME_SAMPLES - filled);
                    if (read < 0) {
                        LimeLog.warning("Microphone: read failed: " + read);
                        return;
                    }
                    filled += read;
                }

                if (running) {
                    MoonBridge.sendMicrophonePcm(frame);
                }
            }
        } catch (IllegalStateException e) {
            LimeLog.warning("Microphone: capture failed: " + e);
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            if (echoCanceler != null) {
                echoCanceler.release();
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
            }
            MoonBridge.releaseMicrophoneEncoder();
            LimeLog.info("Microphone: capture stopped");
        }
    }

    // If a Bluetooth headset microphone is available, recording could be routed to it,
    // which would drop the headset into low quality call mode. Pin the phone's mic instead.
    // Wired and USB headset mics are left alone since they don't have that problem.
    private void preferBuiltInMicOverBluetooth(AudioRecord recorder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo builtInMic = null;
        boolean hasBluetoothMic = false;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                    builtInMic = device;
                    break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    hasBluetoothMic = true;
                    break;
                default:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        hasBluetoothMic = true;
                    }
                    break;
            }
        }

        if (hasBluetoothMic && builtInMic != null) {
            recorder.setPreferredDevice(builtInMic);
            LimeLog.info("Microphone: using the built-in mic instead of the Bluetooth headset");
        }
    }
}
