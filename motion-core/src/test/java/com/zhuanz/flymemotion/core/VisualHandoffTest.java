package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VisualHandoffTest {
    @Test public void logicalHideWhileForegroundIsStillVisibleMustNotRestore() {
        assertFalse(VisualHandoff.nativeHidden(false, 1, true));
        assertFalse(VisualHandoff.nativeHidden(false, 0, true));
        assertTrue(VisualHandoff.nativeHidden(false, 0, false));
    }
    @Test public void relockOrShadeLockedNeverCountsAsCompletedUnlock() {
        assertFalse(VisualHandoff.nativeHidden(true, 0, false));
        assertFalse(VisualHandoff.nativeHidden(false, 2, false));
    }
    @Test public void nativeExpandedHeightCannotFinishPartialPresentedRebound() {
        var expanded = MotionFrame.sample(2000, 0, 2000, true, 1);
        var partialExpanded = expanded.withPresentationProgress(.02f, 1);
        assertFalse(VisualHandoff.settled(1, partialExpanded, true, true, false));
        assertTrue(VisualHandoff.settled(1, expanded, false, true, false));
    }
    @Test public void positiveOvershootMustSettleBeforeReboundCoverageRetires() {
        assertFalse(VisualHandoff.settled(1, MotionFrame.sample(2075, 0, 2000, true, 1), true, true, false));
    }
    @Test public void collapsingNeedsDrawnSceneAndActuallyClosedPresentation() {
        var closed = MotionFrame.sample(0, 0, 2000, true, 1);
        assertFalse(VisualHandoff.settled(0, closed, false, true, false));
        assertFalse(VisualHandoff.settled(0, closed.withPresentationProgress(.1f, 1), true, true, false));
        assertTrue(VisualHandoff.settled(0, closed, true, true, false));
    }
    @Test public void authenticationAndUnmovedTapKeepTheirNativeHandoff() {
        var secure = MotionFrame.sample(1000, 0, 2000, false, 1);
        assertTrue(VisualHandoff.settled(.5f, secure, false, true, true));
        assertTrue(VisualHandoff.settled(1, secure, false, false, false));
    }
    @Test public void readinessCatchupWithSpringIncludesTheWholeMovingSheet() {
        var frame = MotionFrame.sample(2080, 0, 2000, true, 1).withPresentationProgress(.8f, 1);
        assertEquals(-320, frame.contentY(), .001f);
        assertEquals(1680, frame.wallpaperHeight(), .001f);
        assertEquals(-320, frame.blurY(), .001f);
        assertEquals(.8f, frame.wallpaperProgress(), 0);
        assertEquals(2080, frame.rawHeight(), 0);
    }
    @Test public void nativeOffsetExtendsCoverageWithoutChangingPhysicsOrAlpha() {
        var base = MotionFrame.sample(1000, 0, 2000, true, 1);
        var covered = base.coveringContent(base.contentY() + 22);
        assertEquals(1022, covered.wallpaperHeight(), 0);
        assertEquals(base.contentY(), covered.contentY(), 0);
        assertEquals(base.wallpaperAlpha(), covered.wallpaperAlpha(), 0);
        assertEquals(2000, base.coveringContent(75).wallpaperHeight(), 0);
        assertEquals(1000, base.coveringContent(-1200).wallpaperHeight(), 0);
        assertThrows(IllegalArgumentException.class, () -> base.coveringContent(Float.NaN));
    }
    @Test public void coverageContainsEveryFrameOfReversalAndOvershootCatchup() {
        var curve = new SpringCurve(200, .81f, 30, 1500);
        for (int i = 0; i <= 240; i++) {
            float raw = 500 + 1500 * curve.interpolate(i / 240f);
            var frame = MotionFrame.sample(raw, 0, 2000, true, 1).withPresentationProgress(i / 240f, 1);
            assertTrue(frame.wallpaperHeight() + .001f >= Math.min(2000, 2000 + frame.contentY()));
            assertTrue(frame.wallpaperHeight() <= 2000);
        }
    }
}
