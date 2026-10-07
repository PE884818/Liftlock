package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackdropBlendTest {
    @Test public void wallpaperOnlyNeverQualifiesTheReturnFrame() {
        var scene = new ScenePresentation(); scene.open(10);
        var blend = new BackdropBlend();
        var gate = new RevealGate(); gate.reset(2000, 0);
        var drag = MotionFrame.sample(1000, -500, 2000, 2400, true, 1);
        // A committed live HOME fallback permits the finger's wallpaper edge to move.
        var frame = gate.compose(drag, 2000, true, 1);
        assertEquals(-1200, frame.contentY(), .001);
        assertEquals(1200, frame.wallpaperHeight(), .001);
        assertFalse(scene.ready()); assertEquals(-1, blend.reveal(1, scene.ready()));
        assertFalse(blend.exposed()); assertFalse(scene.nativeOwned(true, true));
    }
    @Test public void timerAloneCannotRetireTheFallback() {
        var blend = new BackdropBlend(); long token = blend.reveal(100, true);
        assertEquals(1, blend.alpha(token, 100), .001);
        assertEquals(.5, blend.alpha(token, 190), .001);
        assertEquals(0, blend.alpha(token, 280), .001);
        assertFalse(blend.exposed());
        assertTrue(blend.complete(token)); assertTrue(blend.exposed());
    }
    @Test public void lateFadeCommitCannotRemoveProtectionAfterSurfaceReplacement() {
        var blend = new BackdropBlend(); long old = blend.reveal(100, true);
        blend.protect(); assertFalse(blend.complete(old));
        assertEquals(1, blend.alpha(old, 500), .001); assertFalse(blend.exposed());
        long current = blend.reveal(500, true); assertNotEquals(old, current);
        assertFalse(blend.complete(old)); assertTrue(blend.current(current));
        assertTrue(blend.complete(current));
    }
    @Test public void selectedWindowAndSurfaceMustMatchTheActualPresentation() {
        var scene = new ScenePresentation(); long epoch = scene.bind(10, 20, 30, 7);
        assertTrue(scene.drawn(epoch, 7, true));
        assertTrue(scene.presented(epoch, 7, true, true));
        assertTrue(scene.readyFor(10, 20, 30));
        assertFalse(scene.readyFor(11, 20, 30)); assertFalse(scene.readyFor(10, 21, 30));
        assertFalse(scene.readyFor(10, 20, 31)); assertFalse(scene.readyFor(10, 0, 30));
        scene.invalidate(); assertFalse(scene.readyFor(10, 20, 30));
        assertFalse(scene.presented(epoch, 7, true, true));
    }
    @Test public void unreadyCoverageDoesNotBlurTheExposedDesktop() {
        var gate = new RevealGate(); gate.reset(2000, 0);
        var drag = MotionFrame.sample(500, -9000, 2000, 2400, true, 1);
        var pending = gate.compose(drag, 2000, false, 1);
        assertEquals(2400, pending.wallpaperHeight(), .001);
        assertEquals(600, pending.blurHeight(), .001);
        var fallback = gate.compose(drag, 2000, true, 2);
        assertEquals(pending.contentY(), fallback.contentY(), .001);
        assertEquals(600, fallback.wallpaperHeight(), .001);
        assertEquals(600, fallback.blurHeight(), .001);
        assertEquals(0, MotionFrame.sample(0, 0, 2000, 2400, true, 1).blurHeight(), .001);
        assertEquals(2400, MotionFrame.sample(2100, 0, 2000, 2400, true, 1).blurHeight(), .001);
        assertEquals(2400, MotionFrame.sample(500, 0, 2000, 2400, false, 1).blurHeight(), .001);
    }
    @Test public void delayedFrameAndCancelKeepTheFallbackWithoutCompletingNativeHandoff() {
        var scene = new ScenePresentation(); scene.open(10); scene.prepareNativeExit(10);
        var blend = new BackdropBlend(); assertEquals(-1, blend.reveal(0, scene.ready()));
        long epoch = scene.bind(10, 20, 30, 4); scene.drawn(epoch, 4, true);
        assertFalse(scene.presented(epoch, 4, true, false));
        assertEquals(-1, blend.reveal(300, scene.ready()));
        assertTrue(scene.presented(epoch, 4, true, true));
        long token = blend.reveal(400, scene.ready());
        scene.invalidate(); blend.protect();
        assertFalse(blend.complete(token)); assertFalse(scene.nativeOwned(true, true));
        assertFalse(scene.readyFor(10, 20, 30)); assertFalse(blend.exposed());
    }
}
