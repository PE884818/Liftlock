package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MotionFrameTest {
    @Test public void lockEndpointsAndEarlyAlphaThreshold() {
        var lock = MotionFrame.sample(2000, 0, 2000, true, 1);
        assertEquals(0, lock.contentY(), 0); assertEquals(2000, lock.wallpaperHeight(), 0); assertEquals(1, lock.wallpaperAlpha(), 0);
        var gone = MotionFrame.sample(0, -1000, 2000, true, 1);
        assertEquals(-2000, gone.contentY(), 0); assertEquals(0, gone.wallpaperHeight(), 0);
        assertEquals(0, gone.wallpaperAlpha(), 0); assertEquals(0, gone.blurProgress(), 0);
        assertEquals(0, MotionFrame.sample(40, 0, 2000, true, 1).wallpaperAlpha(), .00001);
        assertEquals(1, MotionFrame.sample(1600, 0, 2000, true, 1).wallpaperAlpha(), .00001);
    }
    @Test public void midpointRevealsWithoutWallpaperTranslation() {
        var frame = MotionFrame.sample(1000, -500, 2000, true, 1);
        assertEquals(-1000, frame.contentY(), 0); assertEquals(1000, frame.wallpaperHeight(), 0);
        assertEquals(.48f / .78f, frame.wallpaperAlpha(), .00001); assertEquals(1, frame.blurProgress(), 0);
        assertEquals(-500, frame.velocity(), 0);
    }
    @Test public void overshootMovesContentButNeverOvercropsNativePanel() {
        var frame = MotionFrame.sample(2075, 100, 2000, true, 1);
        assertEquals(2075, frame.rawHeight(), 0); assertEquals(2000, frame.nativeHeight(), 0);
        assertEquals(75, frame.springHeight(), 0); assertEquals(75, frame.contentY(), 0);
        assertEquals(2000, frame.wallpaperHeight(), 0); assertEquals(1, frame.wallpaperAlpha(), 0);
    }
    @Test public void secureBranchKeepsFullWallpaperAndNativeAuthenticationBlur() {
        var frame = MotionFrame.sample(1000, -500, 2000, false, 1);
        assertEquals(-125, frame.contentY(), 0); assertEquals(2000, frame.wallpaperHeight(), 0);
        assertEquals(1, frame.wallpaperAlpha(), 0); assertEquals(0, frame.blurProgress(), 0);
        assertTrue(frame.authenticationRequired());
    }
    @Test public void usesDisplayHeightEvenWhenNativePanelDistanceDiffers() {
        var frame = MotionFrame.sample(900, 0, 1800, 2400, true, 1);
        assertEquals(-1200, frame.contentY(), 0); assertEquals(1200, frame.wallpaperHeight(), 0);
        assertEquals(900, frame.nativeHeight(), 0);
    }
    @Test public void highVelocitySpringActuallyRetainsItsOvershoot() {
        var spring = new SpringCurve(200, .81f, 30, 1500);
        boolean observed = false;
        for (int i = 0; i < 240; i++) {
            float raw = 500 + 1500 * spring.interpolate(i / 240f);
            var frame = MotionFrame.sample(raw, 0, 2000, true, 1);
            observed |= frame.springHeight() > 0;
        }
        assertTrue(observed);
    }
    @Test public void badSamplesAreRejectedBeforeNativeTransactions() {
        assertThrows(IllegalArgumentException.class, () -> MotionFrame.sample(Float.NaN, 0, 2000, true, 1));
        assertThrows(IllegalArgumentException.class, () -> MotionFrame.sample(1, Float.NaN, 2000, true, 1));
        assertThrows(IllegalArgumentException.class, () -> MotionFrame.sample(1, 0, 0, true, 1));
    }
    @Test public void revealingNewSceneDoesNotJumpToFinger() {
        var gate = new RevealGate(); gate.reset(2000, 1000);
        assertEquals(2000, gate.sample(800, 2000, false, 1100), 0);
        float first = gate.sample(800, 2000, true, 1116);
        assertEquals(800, first, 0);
        for (int i = 2; i <= 10; i++) gate.sample(800, 2000, true, 1100 + i * 16);
        assertEquals(800, gate.sample(800, 2000, true, 1276), 0);
        assertEquals(800, gate.sample(800, 2000, false, 1200), 0); // Stale readiness must not flash full cover.
    }
    @Test public void readySceneTracksDirectionContinuously() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        for (int i = 1; i <= 20; i++) gate.sample(1000, 2000, true, i * 16);
        assertEquals(1000, gate.sample(1000, 2000, true, 336), 0);
        assertEquals(1050, gate.sample(1050, 2000, true, 352), 0);
        assertEquals(2080, gate.sample(2080, 2000, true, 360), 0);
        assertEquals(100, gate.sample(100, 2000, true, 368), 0);
    }
}
