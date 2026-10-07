package com.archos.mediacenter.video.player.upscaling;

/** MediaCodec uses CLOCK_MONOTONIC deadlines, not stream PTS, on its output Surface. */
final class VideoFrameTiming {
    private VideoFrameTiming() {}

    static long presentationTimeNs(long decoderTimestampNs, long nowNs, boolean newImage) {
        // Paused redraws and producers without a monotonic deadline must present now.
        // The native decoder's lookahead is 200 ms; a >1 s future timestamp is not
        // a valid deadline from this pipeline. Never delay late frames further.
        if (!newImage || decoderTimestampNs <= 0 || decoderTimestampNs - nowNs > 1_000_000_000L)
            return nowNs;
        return Math.max(nowNs, decoderTimestampNs);
    }
}
