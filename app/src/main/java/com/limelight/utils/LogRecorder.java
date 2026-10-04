package com.limelight.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records this app's logcat output to rotating files, so logs can be shared from the app
 * instead of pulled with adb.
 * <p>
 * Apps may read their own log entries without any permission. A logcat child process writes
 * them to {@code files/logs}; Android kills it along with the app's process group.
 * Recording is off unless the user turns it on in settings.
 */
public class LogRecorder {
    public static final String PREF_KEY = "checkbox_record_logs";

    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "twilight.log";
    private static final int ROTATE_KB = 1024;
    private static final int ROTATED_FILES = 4;

    private static Process logcat;

    /** Starts recording at app launch if the setting is on. */
    public static void startIfEnabled(Context context) {
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false)) {
            start(context);
        }
    }

    /** Applies a change to the setting right away. Logs recorded so far stay available to share. */
    public static void setEnabled(Context context, boolean enabled) {
        if (enabled) {
            start(context);
        } else {
            stop();
        }
    }

    private static synchronized void stop() {
        if (logcat != null) {
            logcat.destroy();
            logcat = null;
        }
    }

    private static synchronized void start(Context context) {
        if (logcat != null) {
            return;
        }

        File dir = new File(context.getFilesDir(), LOG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }

        // Only record from now on. The previous run's recorder already saved its entries, and
        // logcat would otherwise dump the whole ring buffer into the file again.
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try {
            logcat = new ProcessBuilder("logcat",
                    "-v", "threadtime",
                    "-T", since,
                    "-f", new File(dir, LOG_FILE).getAbsolutePath(),
                    "-r", Integer.toString(ROTATE_KB),
                    "-n", Integer.toString(ROTATED_FILES))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            LimeLog.warning("Failed to start log recording: " + e);
        }
    }

    /**
     * Gathers the recorded logs, oldest first, into one file and opens the share sheet for it.
     *
     * @return false if there was nothing to share or the file couldn't be written
     */
    public static boolean share(Context context) {
        File dir = new File(context.getFilesDir(), LOG_DIR);
        File outDir = new File(context.getCacheDir(), LOG_DIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            return false;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(outDir, "twilight-" + stamp + ".txt");
        boolean wroteAny = false;

        try (OutputStream os = new FileOutputStream(out)) {
            String header = "Twilight " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                    "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n" +
                    "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n\n";
            os.write(header.getBytes(StandardCharsets.UTF_8));

            // logcat names rotated files twilight.log.1 (newest) to .N (oldest)
            for (int i = ROTATED_FILES; i >= 0; i--) {
                File f = new File(dir, i == 0 ? LOG_FILE : LOG_FILE + "." + i);
                if (f.isFile()) {
                    try (InputStream is = new FileInputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                    wroteAny = true;
                }
            }
        } catch (IOException e) {
            LimeLog.warning("Failed to collect logs: " + e);
            return false;
        }

        if (!wroteAny) {
            return false;
        }

        // Drop bundles from earlier shares
        File[] old = outDir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.equals(out)) {
                    f.delete();
                }
            }
        }

        Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", out);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, out.getName());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, null);
        if (!(context instanceof android.app.Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(chooser);
        return true;
    }
}
