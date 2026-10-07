package com.archos.mediacenter.video.player.upscaling;

/** Stable preference values, independent of translated labels or menu order. */
public enum UpscalingMode {
    OFF("off", "Off"),
    RAVU("ravu", "RAVU HQ"),
    FSRCNNX("fsrcnnx", "FSRCNNX Ultra"),
    SGSR1("sgsr1", "SGSR1 Efficient");

    public static final String PREFERENCE = "player_upscaling_mode";
    public final String value;
    public final String label;

    UpscalingMode(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public static UpscalingMode fromPreference(String value) {
        for (UpscalingMode mode : values()) if (mode.value.equals(value)) return mode;
        return RAVU;
    }

    public static boolean needsUpscaling(
            int sourceWidth, int sourceHeight, int renderWidth, int renderHeight) {
        // Do not reconstruct a frame being reduced in either axis (including PiP).
        return sourceWidth > 0
                && sourceHeight > 0
                && renderWidth >= sourceWidth
                && renderHeight >= sourceHeight
                && (renderWidth > sourceWidth || renderHeight > sourceHeight);
    }
}
