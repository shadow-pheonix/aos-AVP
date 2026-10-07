package com.archos.mediacenter.video.player.upscaling;

import static org.junit.Assert.*;

import org.junit.Test;

public class UpscalingPolicyTest {
    @Test
    public void fractionallyUpscalesTabletVideoArea() {
        assertTrue(UpscalingMode.needsUpscaling(1920, 1080, 2560, 1440));
        assertTrue(UpscalingMode.needsUpscaling(1920, 1080, 2800, 1575));
    }

    @Test
    public void bypassesNativeLargerAndPipFrames() {
        assertFalse(UpscalingMode.needsUpscaling(2560, 1440, 2560, 1440));
        assertFalse(UpscalingMode.needsUpscaling(3840, 2160, 2560, 1440));
        assertFalse(UpscalingMode.needsUpscaling(1920, 1080, 800, 450));
        assertFalse(UpscalingMode.needsUpscaling(1920, 1080, 2560, 1000));
        assertFalse(UpscalingMode.needsUpscaling(0, 0, 2560, 1440));
    }

    @Test
    public void defaultsToRavuAndRestoresStablePreferenceValues() {
        assertEquals(UpscalingMode.RAVU, UpscalingMode.fromPreference(null));
        assertEquals(UpscalingMode.RAVU, UpscalingMode.fromPreference("unknown"));
        for (UpscalingMode mode : UpscalingMode.values())
            assertEquals(mode, UpscalingMode.fromPreference(mode.value));
    }

    @Test
    public void keepsHdrAndUnknownColorLayoutsOnPlatformConversion() {
        assertFalse(VideoColorInfo.UNKNOWN.canSampleYuv);
        assertFalse(new VideoColorInfo(9, 1, 2, 2, 1, true).canSampleYuv);
        assertFalse(new VideoColorInfo(1, 1, 2, 2, 0, false).canSampleYuv);
        assertTrue(new VideoColorInfo(1, 1, 2, 2, 1, false).canSampleYuv);
        assertTrue(new VideoColorInfo(5, 2, 1, 1, 0, false).canSampleYuv);
    }

    @Test
    public void usesTheDeclaredChromaSitingAndMatrix() {
        VideoColorInfo left = new VideoColorInfo(1, 1, 2, 2, 1, false);
        assertEquals(2, left.chromaScaleX, 0);
        assertEquals(0.5, left.chromaCenterX, 0);
        assertEquals(1, left.chromaCenterY, 0);
        assertEquals(0.2126, left.kr, 1e-6);
        VideoColorInfo center = new VideoColorInfo(1, 2, 2, 2, 2, false);
        assertEquals(1, center.chromaCenterX, 0);
        assertTrue(center.fullRange);
    }

    @Test
    public void normalizesTenBitCodeValuesWithoutEightBitColorBias() {
        VideoColorInfo tenBit = new VideoColorInfo(1, 1, 2, 2, 1, false, 10);
        assertTrue(tenBit.canSampleYuv);
        assertEquals(1023, tenBit.codeMax, 0);
        assertEquals(4, tenBit.codeStep, 0);
        assertFalse(new VideoColorInfo(1, 1, 2, 2, 1, false, 0).canSampleYuv);
    }
}
