package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regressions use the same composition policy as SystemUI, including missing process receipts. */
public final class RevealCompositionTest {
    @Test public void missingSceneKeepsWallpaperCoveredWithoutFreezingFingerMotion() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        for (float raw : new float[]{1900, 1600, 1000, 500, 0, 700, 1800}) {
            var motion = MotionFrame.sample(raw, -1500, 2000, 2400, true, 1);
            var frame = gate.compose(motion, 2000, false, 16);
            assertEquals(motion.contentY(), frame.contentY(), .001f);
            assertEquals(motion.rawHeight(), frame.rawHeight(), 0);
            assertEquals(motion.progress(), frame.progress(), 0);
            assertEquals(motion.velocity(), frame.velocity(), 0);
            assertEquals(2400, frame.wallpaperHeight(), 0);
            assertEquals(1, frame.wallpaperAlpha(), 0);
            assertEquals(1, frame.wallpaperProgress(), 0);
        }
    }
    @Test public void readySceneMatchesCurrentMotionWithoutAddedCatchup() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        var motion = MotionFrame.sample(800, -1000, 2000, true, 1);
        gate.compose(motion, 2000, false, 100);
        var first = gate.compose(motion, 2000, true, 116);
        assertEquals(first.wallpaperHeight() - 2000, first.contentY(), .001f);
        assertEquals(800, first.wallpaperHeight(), 0);
        for (int i = 2; i <= 10; i++) gate.compose(motion, 2000, true, 100 + i * 16);
        var following = gate.compose(motion, 2000, true, 276);
        assertEquals(motion.wallpaperHeight(), following.wallpaperHeight(), .001);
        assertEquals(motion.wallpaperAlpha(), following.wallpaperAlpha(), .00001);
        assertEquals(motion.blurProgress(), following.blurProgress(), 0);
    }
    @Test public void lostPresentationProofRestoresOpaqueCoverageWithoutResettingInput() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        var motion = MotionFrame.sample(500, -500, 2000, true, 1);
        for (int i = 1; i <= 10; i++) gate.compose(motion, 2000, true, i * 16);
        var protectedFrame = gate.compose(motion, 2000, false, 180);
        assertEquals(-1500, protectedFrame.contentY(), 0);
        assertEquals(2000, protectedFrame.wallpaperHeight(), 0);
        assertEquals(1, protectedFrame.wallpaperAlpha(), 0);
        var dragged = gate.compose(MotionFrame.sample(200, -500, 2000, true, 1), 2000, false, 196);
        assertEquals(2000, dragged.wallpaperHeight(), 0);
        var reversed = gate.compose(MotionFrame.sample(800, 500, 2000, true, 1), 2000, false, 212);
        assertEquals(2000, reversed.wallpaperHeight(), 0);
        assertEquals(-1200, reversed.contentY(), 0);
        gate.reset(2000, 228); // A different binding must start fully covered.
        assertEquals(2000, gate.compose(motion, 2000, false, 244).wallpaperHeight(), 0);
    }
    @Test public void coldSceneFollowsInputWhileWallpaperKeepsSafeCoverage() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        float[] heights = {1100, 1080, 1140, 1210, 1300};
        float previous = -900;
        for (float raw : heights) {
            var frame = gate.compose(MotionFrame.sample(raw, 100, 2000, true, 1), 2000, false, 16);
            assertEquals(raw - 2000, frame.contentY(), .001f);
            assertEquals(2000, frame.wallpaperHeight(), 0);
            assertTrue(Math.abs(frame.contentY() - previous) <= 100);
            previous = frame.contentY();
        }
    }
    @Test public void pendingSceneDoesNotDiscardSpringOvershoot() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        var frame = gate.compose(MotionFrame.sample(2075, 100, 2000, true, 1), 2000, false, 16);
        assertEquals(75, frame.springHeight(), 0); assertEquals(75, frame.contentY(), 0);
        assertEquals(2000, frame.nativeHeight(), 0); assertEquals(2000, frame.wallpaperHeight(), 0);
    }
    @Test public void secureGestureNeverBorrowsReadinessToRevealApp() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        var motion = MotionFrame.sample(1000, -300, 2000, false, 1);
        assertSame(motion, gate.compose(motion, 2000, true, 200));
        assertEquals(-125, motion.contentY(), 0); assertEquals(2000, motion.wallpaperHeight(), 0);
        assertEquals(1, motion.wallpaperAlpha(), 0); assertEquals(0, motion.blurProgress(), 0);
    }
    @Test public void preparedSceneFollowsTheVeryFirstFastFrameWithoutCatchup() {
        var gate = new RevealGate(); gate.reset(2000, 0, true);
        for (float height : new float[]{1970, 1200, 100, 0, 800, 1900}) {
            var motion = MotionFrame.sample(height, -20000, 2000, 2400, true, .4f);
            var frame = gate.compose(motion, 2000, true, 8, .4f);
            assertEquals(motion.contentY(), frame.contentY(), .001f);
            assertEquals(motion.wallpaperHeight(), frame.wallpaperHeight(), .001f);
            assertEquals(motion.blurProgress(), frame.blurProgress(), .001f);
        }
    }
    @Test public void catchupNeverExposesAGapBetweenControlsAndWallpaper() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        for (int i = 1; i < 12; i++) {
            var frame = gate.compose(MotionFrame.sample(100, -30000, 2000, 2400, true, .4f), 2000, i > 3, i * 8, .4f);
            assertTrue(frame.wallpaperHeight() >= 2400 + frame.contentY() - frame.springHeight());
            assertEquals(-2280, frame.contentY(), .001f);
            if(i>3)assertEquals(frame.wallpaperProgress(), frame.progress(), 0);
        }
    }
}
