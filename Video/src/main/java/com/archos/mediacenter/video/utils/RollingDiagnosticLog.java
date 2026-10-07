package com.archos.mediacenter.video.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded, synchronized disk history. Only diagnostic worker threads write to it. */
final class RollingDiagnosticLog implements AutoCloseable {
    final File current, previous;
    private final long limit;
    private BufferedOutputStream output;
    private long bytes, flushedAt;

    RollingDiagnosticLog(File directory, String name, long limit) {
        current = new File(directory, name + ".log");
        previous = new File(directory, name + ".previous.log");
        this.limit = limit;
    }

    synchronized void append(String text) throws IOException {
        // Bound a single driver message/stack as well as the overall files.
        if (text.length() > 8000) text = text.substring(0, 8000) + " [truncated]";
        byte[] data = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (data.length > limit) throw new IOException("Diagnostic entry exceeds file limit");
        if (output == null) {
            if (!current.getParentFile().isDirectory() && !current.getParentFile().mkdirs())
                throw new IOException("Cannot create diagnostic directory");
            bytes = current.length();
            output = new BufferedOutputStream(new FileOutputStream(current, true));
        }
        if (bytes + data.length > limit) {
            close();
            if (previous.exists() && !previous.delete())
                throw new IOException("Cannot rotate diagnostic history");
            if (!current.renameTo(previous)) throw new IOException("Cannot rotate diagnostic log");
            output = new BufferedOutputStream(new FileOutputStream(current));
            bytes = 0;
        }
        output.write(data);
        bytes += data.length;
        if (System.currentTimeMillis() - flushedAt >= 1000) flush();
    }

    synchronized void flush() throws IOException {
        if (output != null) output.flush();
        flushedAt = System.currentTimeMillis();
    }

    @Override
    public synchronized void close() throws IOException {
        if (output != null) {
            output.close();
            output = null;
        }
    }
}
