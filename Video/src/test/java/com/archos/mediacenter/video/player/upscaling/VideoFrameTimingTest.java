package com.archos.mediacenter.video.player.upscaling;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VideoFrameTimingTest {
    private static final long NOW = 392_415_800_000_000L;

    @Test
    public void keepsDecoderLookaheadAtTheFinalVideoSurface() {
        assertEquals(
                NOW + 200_000_000L,
                VideoFrameTiming.presentationTimeNs(NOW + 200_000_000L, NOW, true));
    }

    @Test
    public void lateFrameAndPausedRedrawCannotAddAnotherPresentationDelay() {
        assertEquals(NOW, VideoFrameTiming.presentationTimeNs(NOW - 90_000_000L, NOW, true));
        assertEquals(NOW, VideoFrameTiming.presentationTimeNs(NOW + 200_000_000L, NOW, false));
    }

    @Test
    public void rejectsMissingAndNonMonotonicProducerTimestamps() {
        assertEquals(NOW, VideoFrameTiming.presentationTimeNs(0, NOW, true));
        assertEquals(NOW, VideoFrameTiming.presentationTimeNs(1_000_000L, NOW, true));
        assertEquals(NOW, VideoFrameTiming.presentationTimeNs(NOW + 10_000_000_000L, NOW, true));
    }
}
