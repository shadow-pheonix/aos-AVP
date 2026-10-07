package com.archos.mediacenter.video.utils;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class RollingDiagnosticLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void noisyNativeLogsRemainBoundedAndRetainLatestCompleteUtf8Messages() throws Exception {
        File folder = temporary.newFolder();
        try (RollingDiagnosticLog log = new RollingDiagnosticLog(folder, "native", 256)) {
            for (int i = 0; i < 50; i++) log.append("frame " + i + " — GPU driver status");
            log.flush();
            assertTrue(log.current.length() <= 256);
            assertTrue(log.previous.length() <= 256);
            assertTrue(
                    Files.readString(log.current.toPath())
                            .contains("frame 49 — GPU driver status"));
            assertFalse(Files.readString(log.previous.toPath()).contains("\uFFFD"));
        }
    }

    @Test
    public void restartAppendsThenRotatesExistingLogRatherThanDiscardingHistory() throws Exception {
        File folder = temporary.newFolder();
        try (RollingDiagnosticLog log = new RollingDiagnosticLog(folder, "events", 64)) {
            log.append("previous process failed EGL presentation");
        }
        try (RollingDiagnosticLog log = new RollingDiagnosticLog(folder, "events", 64)) {
            log.append("new process exported diagnostics");
            log.flush();
            assertTrue(Files.readString(log.previous.toPath()).contains("previous process"));
            assertTrue(Files.readString(log.current.toPath()).contains("new process"));
            assertEquals(
                    "new process exported diagnostics\n".getBytes(StandardCharsets.UTF_8).length,
                    log.current.length());
        }
    }
}
