package com.archos.mediacenter.video.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.archos.mediacenter.video.BuildConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagnosticsExporter {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticsExporter.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private DiagnosticsExporter() {}

    public static void export(Context context) {
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                File bundle = createBundle(appContext);
                mainHandler.post(() -> shareBundle(context, bundle));
            } catch (Exception e) {
                log.error("Diagnostics export failed", e);
                mainHandler.post(() -> Toast.makeText(
                        context,
                        "Could not create diagnostics: " + e.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-export").start();
    }

    private static File createBundle(Context context) throws IOException {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) {
            throw new IOException("External files directory unavailable");
        }

        File logDir = new File(externalFiles, "logback");
        File diagnosticsDir = new File(externalFiles, "diagnostics");
        if (!diagnosticsDir.isDirectory() && !diagnosticsDir.mkdirs()) {
            throw new IOException("Could not create diagnostics directory");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File outFile = new File(diagnosticsDir, "nova-diagnostics-" + stamp + ".zip");

        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile)))) {
            addText(zip, "diagnostics-info.txt", buildMetadata(context, logDir));

            if (logDir.isDirectory()) {
                File[] files = logDir.listFiles();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File file : files) {
                        if (file.isFile()
                                && (file.getName().startsWith("nova")
                                    || "last-crash.txt".equals(file.getName())
                                    || "logback.xml".equals(file.getName()))) {
                            addFile(zip, file, "logs/" + file.getName());
                        }
                    }
                }
            }
        }

        log.info("Diagnostics bundle created: {} ({} bytes)", outFile.getName(), outFile.length());
        return outFile;
    }

    private static String buildMetadata(Context context, File logDir) {
        StringBuilder info = new StringBuilder();
        info.append("Nova diagnostics\n");
        info.append("generated=").append(new Date()).append('\n');
        info.append("app_version=").append(BuildConfig.VERSION_NAME).append('\n');
        info.append("app_version_code=").append(BuildConfig.VERSION_CODE).append('\n');
        info.append("package=").append(BuildConfig.APPLICATION_ID).append('\n');
        info.append("manufacturer=").append(Build.MANUFACTURER).append('\n');
        info.append("model=").append(Build.MODEL).append('\n');
        info.append("device=").append(Build.DEVICE).append('\n');
        info.append("android_release=").append(Build.VERSION.RELEASE).append('\n');
        info.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
        info.append("abis=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        info.append("max_heap_mb=").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append('\n');
        info.append("locale=").append(Locale.getDefault().toLanguageTag()).append('\n');
        info.append("log_directory_present=").append(logDir.isDirectory()).append('\n');
        return info.toString();
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, File file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
            }
        }
        zip.closeEntry();
    }

    private static void shareBundle(Context context, File bundle) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".provider",
                    bundle);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Nova diagnostics " + BuildConfig.VERSION_NAME);
            send.setClipData(android.content.ClipData.newRawUri("Nova diagnostics", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "Share Nova diagnostics");
            if (!(context instanceof android.app.Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(chooser);
        } catch (Exception e) {
            log.error("Could not share diagnostics bundle", e);
            Toast.makeText(context, "Diagnostics created, but sharing failed", Toast.LENGTH_LONG).show();
        }
    }
}
