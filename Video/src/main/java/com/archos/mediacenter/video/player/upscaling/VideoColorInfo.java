package com.archos.mediacenter.video.player.upscaling;

/** Immutable FFmpeg color metadata. Unknown layouts use the platform's color conversion. */
public final class VideoColorInfo {
    public static final VideoColorInfo UNKNOWN = new VideoColorInfo(0, 0, 0, 0, 0, false, 0);
    public final float kr,
            kb,
            chromaScaleX,
            chromaScaleY,
            chromaCenterX,
            chromaCenterY,
            codeMax,
            codeStep;
    public final boolean fullRange, canSampleYuv;
    private final int space, range, chromaX, chromaY, location, depth;
    private final boolean hdr;

    public VideoColorInfo(
            int space, int range, int chromaX, int chromaY, int location, boolean hdr) {
        this(space, range, chromaX, chromaY, location, hdr, 8);
    }

    public VideoColorInfo(
            int space, int range, int chromaX, int chromaY, int location, boolean hdr, int depth) {
        this.space = space;
        this.range = range;
        this.chromaX = chromaX;
        this.chromaY = chromaY;
        this.location = location;
        this.hdr = hdr;
        this.depth = depth;
        codeMax = depth >= 8 && depth <= 16 ? (1 << depth) - 1 : 255;
        codeStep = depth >= 8 && depth <= 16 ? 1 << (depth - 8) : 1;
        kr = space == 1 ? 0.2126f : space == 9 ? 0.2627f : 0.299f;
        kb = space == 1 ? 0.0722f : space == 9 ? 0.0593f : 0.114f;
        fullRange = range == 2;
        // Native metadata stores subsampling shifts plus one; zero means unknown.
        chromaScaleX = chromaX > 0 ? (1 << (chromaX - 1)) : 1;
        chromaScaleY = chromaY > 0 ? (1 << (chromaY - 1)) : 1;
        chromaCenterX = (location == 1 || location == 3 || location == 5) ? 0.5f : chromaScaleX / 2;
        chromaCenterY =
                (location == 3 || location == 4)
                        ? 0.5f
                        : (location == 5 || location == 6) ? chromaScaleY - 0.5f : chromaScaleY / 2;
        canSampleYuv =
                !hdr
                        && depth >= 8
                        && depth <= 16
                        && (space == 1 || space == 5 || space == 6 || space == 9)
                        && (range == 1 || range == 2)
                        && chromaX > 0
                        && chromaX <= 3
                        && chromaY > 0
                        && chromaY <= 3
                        && (chromaScaleX == 1 && chromaScaleY == 1
                                || location >= 1 && location <= 6);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VideoColorInfo)) return false;
        VideoColorInfo c = (VideoColorInfo) other;
        return space == c.space
                && range == c.range
                && chromaX == c.chromaX
                && chromaY == c.chromaY
                && location == c.location
                && hdr == c.hdr
                && depth == c.depth;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(space, range, chromaX, chromaY, location, hdr, depth);
    }
}
