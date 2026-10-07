package com.archos.mediacenter.video.utils;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlaybackDiagnosticsTest {
    @Test
    public void exportedZipRetainsGpuFailureAfterPlayerAndRecorderClose() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PlaybackDiagnostics original = new PlaybackDiagnostics(context);
        original.event("mode_selected", "FSRCNNX Ultra");
        original.capture(
                "gpu_failure",
                "Active: no\nSource: 1920×816 → Render: 2560×1088\n"
                        + "GPU upscaling unavailable: GL 0x505\nlast graph build=850 ms");
        original.end();
        assertTrue(original.flush());
        original.shutdownForTest();

        // Export uses a new recorder, with no live Player and no in-memory snapshot.
        File bundle = DiagnosticsExporter.createBundle(context);
        try (ZipFile zip = new ZipFile(bundle)) {
            String snapshot =
                    new String(
                            zip.getInputStream(zip.getEntry("playback/last-playback.txt"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8);
            assertTrue(snapshot.contains("GL 0x505"));
            assertTrue(snapshot.contains("2560×1088"));
            assertTrue(snapshot.contains("retained snapshot"));
            String events =
                    new String(
                            zip.getInputStream(zip.getEntry("playback/playback-events.log"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8);
            assertTrue(events.contains("FSRCNNX Ultra"));
            assertTrue(events.contains("session_closed"));
            assertNotNull(zip.getEntry("playback/collector-status.txt"));
            assertNotNull(zip.getEntry("playback/process-exits.txt"));
        } finally {
            PlaybackDiagnostics.get(context).shutdownForTest();
        }
    }
}
