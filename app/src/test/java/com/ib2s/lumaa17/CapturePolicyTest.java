package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import org.junit.Test;

public class CapturePolicyTest {
    @Test public void exposureUsesDeviceStepAndClamps() {
        assertEquals(-1, CapturePolicy.exposureIndex(-0.3, 1.0/3, -6, 6));
        assertEquals(6, CapturePolicy.exposureIndex(12, 1.0/3, -6, 6));
        assertEquals(-2, CapturePolicy.exposureIndex(-4, .5, -2, 4));
        assertEquals(0, CapturePolicy.exposureIndex(1, 0, -3, 3));
        assertEquals(0, CapturePolicy.exposureIndex(Double.NaN, .5, -3, 3));
    }
    @Test public void shutterNeverExceedsReportedSensorRange() {
        long last = 0;
        for (int p=-10; p<=110; p++) {
            long n = CapturePolicy.shutterFromProgress(p, 125_000, 250_000_000);
            assertTrue(n >= 125_000 && n <= 250_000_000);
            assertTrue(n >= last); last = n;
        }
        assertEquals(125_000, CapturePolicy.shutterFromProgress(-1, 125_000, 250_000_000));
        assertEquals(250_000_000, CapturePolicy.shutterFromProgress(101, 125_000, 250_000_000));
    }
    @Test public void orientationBoundariesPreserveSavedMediaDirection() {
        assertEquals(0, CapturePolicy.rotationDegrees(44));
        assertEquals(270, CapturePolicy.rotationDegrees(45));
        assertEquals(180, CapturePolicy.rotationDegrees(135));
        assertEquals(90, CapturePolicy.rotationDegrees(225));
        assertEquals(0, CapturePolicy.rotationDegrees(315));
    }
    @Test public void bitratesScaleWithRealResolution() {
        assertTrue(CapturePolicy.bitrate(720, true) < CapturePolicy.bitrate(1080, false));
        assertEquals(24_000_000, CapturePolicy.bitrate(1080, true));
        assertEquals(16_000_000, CapturePolicy.bitrate(1080, false));
    }
}
