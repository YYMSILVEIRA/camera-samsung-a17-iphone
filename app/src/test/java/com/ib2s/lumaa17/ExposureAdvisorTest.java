package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class ExposureAdvisorTest {
    @Test public void darkIndoorSceneGainsExposureButStaysBounded() {
        ExposureAdvisor.Sample room = new ExposureAdvisor.Sample(48, 145, 0);
        double ev = 0;
        for (int i = 0; i < 30; i++) ev = ExposureAdvisor.next(ev, room, false);
        assertTrue(ev > 0); assertTrue(ev <= 1);
        ev = 0;
        for (int i = 0; i < 30; i++) ev = ExposureAdvisor.next(ev, room, true);
        assertTrue(ev > 0); assertTrue(ev <= 2.0 / 3);
    }
    @Test public void brightWindowsProtectHighlightsEvenWithDarkSubject() {
        ExposureAdvisor.Sample backlit = new ExposureAdvisor.Sample(40, 252, .04);
        assertTrue(ExposureAdvisor.next(.67, backlit, false) < .67);
        assertEquals(0, ExposureAdvisor.next(0, backlit, false), .0001);
    }
    @Test public void settledSceneDoesNotPumpExposureAndBlankPreviewIsIgnored() {
        for (int median = 98; median <= 135; median++)
            assertEquals(.5, ExposureAdvisor.next(.5, new ExposureAdvisor.Sample(median, 210, 0), true), .0001);
        assertEquals(.5, ExposureAdvisor.next(.5, new ExposureAdvisor.Sample(0, 0, 0), false), .0001);
    }
    @Test public void previewMeasurementFindsSubjectAndSmallClippedAreas() {
        int[] image = new int[100 * 100]; Arrays.fill(image, 0xff404040);
        for (int i = 0; i < 200; i++) image[i] = 0xffffffff;
        ExposureAdvisor.Sample scene = ExposureAdvisor.measure(image, 100, 100);
        assertEquals(64, scene.median);
        assertEquals(.02, scene.clipped, .0001);
        assertTrue(ExposureAdvisor.next(.5, scene, false) < .5);
    }
    @Test public void videoChangesMoreSlowlyThanPhoto() {
        ExposureAdvisor.Sample room = new ExposureAdvisor.Sample(50, 180, 0);
        assertTrue(ExposureAdvisor.next(0, room, true) < ExposureAdvisor.next(0, room, false));
    }
    @Test public void fpsSelectionUsesAdvertisedRangesAndKeepsLowLightHeadroom() {
        int[][] ranges = {{30, 30}, {15, 30}, {7, 30}, {60, 60}};
        assertArrayEquals(new int[]{15,30}, CapturePolicy.frameRate(ranges, true, false));
        assertArrayEquals(new int[]{7,30}, CapturePolicy.frameRate(ranges, false, false));
        assertArrayEquals(new int[]{30,30}, CapturePolicy.frameRate(ranges, true, true));
        assertArrayEquals(new int[]{7,30}, CapturePolicy.frameRate(new int[][]{{7,30}}, true, false));
        assertNull(CapturePolicy.frameRate(new int[][]{{60,60}}, true, false));
    }
}
