package com.archos.mediacenter.video.player.upscaling;

import java.util.Locale;

/** A single published snapshot avoids torn frame/configuration reads from the UI or exporter. */
public final class UpscalingDiagnostics {
    public final UpscalingMode selected;
    public final int sourceWidth, sourceHeight, renderWidth, renderHeight;
    public final boolean active;
    public final String backend, chroma, reason;
    public final long presentedFrames, coalescedFrames;
    public final double renderMs;

    public UpscalingDiagnostics(
            UpscalingMode mode,
            int sw,
            int sh,
            int rw,
            int rh,
            boolean active,
            String backend,
            String chroma,
            String reason,
            long frames,
            long coalesced,
            double renderMs) {
        selected = mode;
        sourceWidth = sw;
        sourceHeight = sh;
        renderWidth = rw;
        renderHeight = rh;
        this.active = active;
        this.backend = backend;
        this.chroma = chroma;
        this.reason = reason;
        presentedFrames = frames;
        coalescedFrames = coalesced;
        this.renderMs = renderMs;
    }

    public String describe() {
        return String.format(
                Locale.US,
                "Upscaling: %s\n"
                    + "Source: %d×%d → Render: %d×%d\n"
                    + "Active: %s%s\n"
                    + "Renderer: %s\n"
                    + "Chroma: %s\n"
                    + "Presented: %d; coalesced notifications: %d\n"
                    + "CPU render + swap: %s; GPU timing: unavailable; decoder drops: unavailable",
                selected.label,
                sourceWidth,
                sourceHeight,
                renderWidth,
                renderHeight,
                active ? "yes" : "no",
                reason.isEmpty() ? "" : " (" + reason + ")",
                backend,
                chroma,
                presentedFrames,
                coalescedFrames,
                renderMs < 0 ? "unavailable" : String.format(Locale.US, "%.2f ms", renderMs));
    }
}
