package com.archos.mediacenter.video.utils;

import android.content.Context;
import android.os.Build;

import com.archos.mediacenter.video.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DiagnosticsCrashHandler implements Thread.UncaughtExceptionHandler {
    private static volatile boolean installed;
    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private DiagnosticsCrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
        this.appContext = context.getApplicationContext();
        this.previous = previous;
    }

    public static synchronized void install(Context context) {
        if (installed || context == null) {
            return;
        }
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new DiagnosticsCrashHandler(context, current));
        installed = true;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            File externalFiles = appContext.getExternalFilesDir(null);
            if (externalFiles != null) {
                File logDir = new File(externalFiles, "logback");
                if (logDir.isDirectory() || logDir.mkdirs()) {
                    File crashFile = new File(logDir, "last-crash.txt");
                    try (PrintWriter writer = new PrintWriter(new FileOutputStream(crashFile, false))) {
                        writer.println("Nova uncaught Java crash");
                        writer.println("timestamp=" + new SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date()));
                        writer.println("thread=" + (thread == null ? "unknown" : thread.getName()));
                        writer.println("app_version=" + BuildConfig.VERSION_NAME);
                        writer.println("device=" + Build.MANUFACTURER + " " + Build.MODEL);
                        writer.println("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
                        writer.println(PlaybackDiagnostics.currentForCrash());
                        writer.println();
                        if (throwable != null) {
                            throwable.printStackTrace(writer);
                        }
                        writer.flush();
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }
}
