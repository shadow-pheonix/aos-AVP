package com.archos.mediacenter.video.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;

import com.archos.mediacenter.video.BuildConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Persistent app-owned playback evidence. No filesystem work runs on the video render thread. */
public final class PlaybackDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(PlaybackDiagnostics.class);
    private static volatile PlaybackDiagnostics instance;
    private final Context context;
    private final HandlerThread thread = new HandlerThread("Nova-diagnostics");
    private final Handler io, main = new Handler(Looper.getMainLooper());
    private final File directory;
    private final RollingDiagnosticLog events, nativeLogs;
    private final AtomicFile snapshotFile;
    private volatile String snapshot = "", captureStatus = "not started in this process";
    private volatile Supplier<String> gpuObserver;
    private volatile boolean active;
    private volatile long heartbeatAt;
    private volatile String session = "none";
    private java.lang.Process logcat;
    private final Runnable heartbeat =
            new Runnable() {
                @Override
                public void run() {
                    heartbeatAt = SystemClock.uptimeMillis();
                    if (active) main.postDelayed(this, 1000);
                }
            };
    private final Runnable stopCapture = this::stopNativeCapture;
    private final Runnable sample =
            new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    Supplier<String> observer = gpuObserver;
                    if (observer != null) {
                        try {
                            capture("gpu_sample", observer.get());
                        } catch (RuntimeException e) {
                            failure("diagnostic_observer_failed", e);
                        }
                    }
                    long delay = SystemClock.uptimeMillis() - heartbeatAt;
                    if (delay > 2000)
                        event(
                                "main_thread_stall",
                                "heartbeat_age_ms="
                                        + delay
                                        + "\n"
                                        + stack(Looper.getMainLooper().getThread()));
                    io.postDelayed(this, 5000);
                }
            };

    public static PlaybackDiagnostics get(Context context) {
        if (instance == null)
            synchronized (PlaybackDiagnostics.class) {
                if (instance == null)
                    instance = new PlaybackDiagnostics(context.getApplicationContext());
            }
        return instance;
    }

    PlaybackDiagnostics(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "playback-diagnostics");
        events = new RollingDiagnosticLog(directory, "playback-events", 1024 * 1024);
        nativeLogs = new RollingDiagnosticLog(directory, "app-logcat", 2 * 1024 * 1024);
        snapshotFile = new AtomicFile(new File(directory, "last-playback.txt"));
        thread.start();
        io = new Handler(thread.getLooper());
    }

    public void begin(String sourceScheme, String mode) {
        session = Long.toHexString(System.currentTimeMillis()) + "-" + Process.myPid();
        active = true;
        heartbeatAt = SystemClock.uptimeMillis();
        main.removeCallbacks(heartbeat);
        main.post(heartbeat);
        io.removeCallbacks(sample);
        io.removeCallbacks(stopCapture);
        event(
                "session_open",
                "app="
                        + BuildConfig.VERSION_NAME
                        + " mode="
                        + mode
                        + " source_scheme="
                        + sourceScheme
                        + " pid="
                        + Process.myPid());
        capture("session_open", "Preparing playback; selected=" + mode);
        io.post(this::startNativeCapture);
        io.postDelayed(sample, 5000);
    }

    public void observeGpu(Supplier<String> observer) {
        gpuObserver = observer;
    }

    public void end() {
        active = false;
        gpuObserver = null;
        main.removeCallbacks(heartbeat);
        io.removeCallbacks(sample);
        event("session_closed", "Last renderer snapshot is retained below and on disk");
        io.removeCallbacks(stopCapture);
        io.postDelayed(stopCapture, 30_000); // include decoder teardown messages
    }

    public void event(String name, String detail) {
        String entry = stamp() + " session=" + session + " " + name + "\n" + detail;
        io.post(
                () -> {
                    try {
                        events.append(entry);
                        events.flush();
                    } catch (IOException e) {
                        log.warn("Cannot write playback diagnostic history", e);
                    }
                });
    }

    public void failure(String name, Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        event(name, text.toString());
    }

    public void capture(String name, String detail) {
        snapshot =
                "captured="
                        + stamp()
                        + "\nsession="
                        + session
                        + "\nstate="
                        + name
                        + "\napp="
                        + BuildConfig.VERSION_NAME
                        + "\n"
                        + detail;
        String saved = snapshot;
        event(name, detail);
        io.post(() -> writeSnapshot(saved));
    }

    private void writeSnapshot(String text) {
        FileOutputStream output = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Cannot create diagnostics directory");
            output = snapshotFile.startWrite();
            output.write(text.getBytes(StandardCharsets.UTF_8));
            snapshotFile.finishWrite(output);
        } catch (IOException e) {
            if (output != null) snapshotFile.failWrite(output);
            log.warn("Cannot retain playback snapshot", e);
        }
    }

    public static String currentForCrash() {
        PlaybackDiagnostics recorder = instance;
        return recorder == null ? "No playback recorder in this process" : recorder.snapshot;
    }

    private void startNativeCapture() {
        if (logcat != null) return;
        try {
            java.lang.Process child =
                    new ProcessBuilder(
                                    "/system/bin/logcat",
                                    "--pid=" + Process.myPid(),
                                    "-v",
                                    "threadtime",
                                    "-b",
                                    "main",
                                    "-b",
                                    "system",
                                    "-b",
                                    "crash",
                                    "-T",
                                    "200")
                            .redirectErrorStream(true)
                            .start();
            logcat = child;
            captureStatus =
                    "capturing this app PID=" + Process.myPid() + "; other apps are excluded";
            event("native_logcat_start", captureStatus);
            new Thread(
                            () -> {
                                try (BufferedReader reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        child.getInputStream(),
                                                        StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        nativeLogs.append(line);
                                        if (line.startsWith("logcat:")
                                                && (line.contains("denied")
                                                        || line.contains("permitted")))
                                            captureStatus = "logcat restricted: " + line;
                                    }
                                    int code = child.waitFor();
                                    event("native_logcat_exit", "exit_code=" + code);
                                } catch (IOException | InterruptedException e) {
                                    failure("native_logcat_reader", e);
                                    if (e instanceof InterruptedException)
                                        Thread.currentThread().interrupt();
                                } finally {
                                    io.post(
                                            () -> {
                                                if (logcat == child) {
                                                    logcat = null;
                                                    captureStatus =
                                                            "logcat exited; see"
                                                                    + " playback-events.log";
                                                }
                                                try {
                                                    nativeLogs.flush();
                                                } catch (IOException e) {
                                                    log.warn("Logcat flush failed", e);
                                                }
                                            });
                                }
                            },
                            "Nova-logcat-reader")
                    .start();
        } catch (IOException | RuntimeException e) {
            captureStatus = "unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            failure("native_logcat_unavailable", e);
        }
    }

    private void stopNativeCapture() {
        java.lang.Process child = logcat;
        logcat = null;
        if (child != null) child.destroy();
        captureStatus = "capture stopped after playback; logs retained";
    }

    boolean flush() {
        CountDownLatch done = new CountDownLatch(1);
        io.post(
                () -> {
                    try {
                        events.flush();
                        nativeLogs.flush();
                    } catch (IOException e) {
                        log.warn("Diagnostic flush failed", e);
                    } finally {
                        done.countDown();
                    }
                });
        try {
            return done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void addToZip(ZipOutputStream zip) throws IOException {
        Supplier<String> observer = gpuObserver;
        if (observer != null) {
            try {
                capture("diagnostics_export", observer.get());
            } catch (RuntimeException e) {
                failure("diagnostic_export_observer_failed", e);
            }
        }
        boolean flushed = flush();
        String retained = snapshot;
        if (retained.isEmpty()) {
            try {
                retained = new String(snapshotFile.readFully(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                retained = "No retained playback snapshot";
            }
        }
        addText(
                zip,
                "playback/last-playback.txt",
                "This is a timestamped retained snapshot, not a claim that playback is active"
                        + " now.\n"
                        + retained);
        addText(
                zip,
                "playback/collector-status.txt",
                "export_pid="
                        + Process.myPid()
                        + "\nplayback_open="
                        + active
                        + "\nflush_completed="
                        + flushed
                        + "\nnative_capture="
                        + captureStatus
                        + "\n"
                        + "Limits: 2 MiB playback events, 4 MiB app logcat; prior process files may"
                        + " be included.\n"
                        + memory());
        synchronized (events) {
            addLogs(zip, events);
        }
        synchronized (nativeLogs) {
            addLogs(zip, nativeLogs);
        }
        addProcessExits(zip);
    }

    private void addLogs(ZipOutputStream zip, RollingDiagnosticLog logs) throws IOException {
        for (File file : new File[] {logs.previous, logs.current})
            if (file.isFile()) {
                zip.putNextEntry(new ZipEntry("playback/" + file.getName()));
                try (InputStream input = new FileInputStream(file)) {
                    copyLimited(input, zip, 2 * 1024 * 1024);
                }
                zip.closeEntry();
            }
    }

    private String memory() {
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        return "java_used_bytes="
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                + "\njava_max_bytes="
                + Runtime.getRuntime().maxMemory()
                + "\nnative_heap_bytes="
                + Debug.getNativeHeapAllocatedSize()
                + "\nsystem_available_bytes="
                + memory.availMem
                + "\nsystem_low_memory="
                + memory.lowMemory
                + "\n";
    }

    private void addProcessExits(ZipOutputStream zip) throws IOException {
        StringBuilder report =
                new StringBuilder(
                        "Historical exits for this app; compare timestamps with the playback"
                                + " session.\n");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager manager =
                        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits =
                        manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
                for (ApplicationExitInfo exit : exits) {
                    report.append("timestamp=")
                            .append(new Date(exit.getTimestamp()))
                            .append(" pid=")
                            .append(exit.getPid())
                            .append(" reason=")
                            .append(exit.getReason())
                            .append(" status=")
                            .append(exit.getStatus())
                            .append(" pss_kib=")
                            .append(exit.getPss())
                            .append(" rss_kib=")
                            .append(exit.getRss())
                            .append(" description=")
                            .append(exit.getDescription())
                            .append('\n');
                    try (InputStream trace = exit.getTraceInputStream()) {
                        if (trace != null) {
                            zip.putNextEntry(
                                    new ZipEntry(
                                            "playback/process-exit-"
                                                    + exit.getTimestamp()
                                                    + "-"
                                                    + exit.getPid()
                                                    + ".trace"));
                            try {
                                copyLimited(trace, zip, 256 * 1024);
                            } finally {
                                zip.closeEntry();
                            }
                            report.append(
                                    "Trace included (limited to 256 KiB; native traces may be"
                                            + " binary).\n");
                        }
                    } catch (IOException | RuntimeException e) {
                        report.append("trace unavailable: ")
                                .append(e.getClass().getSimpleName())
                                .append('\n');
                    }
                }
            } catch (RuntimeException e) {
                report.append("Exit history unavailable: ").append(e.getClass().getSimpleName());
            }
        } else report.append("Requires Android 11 or later");
        addText(zip, "playback/process-exits.txt", report.toString());
    }

    static void addText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void copyLimited(InputStream input, ZipOutputStream zip, int limit)
            throws IOException {
        byte[] buffer = new byte[16384];
        int count, remaining = limit;
        while (remaining > 0
                && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
            zip.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    public static String stack(Thread thread) {
        StringBuilder text = new StringBuilder("thread=" + thread.getName() + "\n");
        for (StackTraceElement frame : thread.getStackTrace()) {
            text.append("  at ").append(frame).append('\n');
            if (text.length() > 6000) break;
        }
        return text.toString();
    }

    void shutdownForTest() throws IOException {
        active = false;
        main.removeCallbacks(heartbeat);
        io.removeCallbacks(sample);
        stopNativeCapture();
        flush();
        events.close();
        nativeLogs.close();
        thread.quitSafely();
    }
}
