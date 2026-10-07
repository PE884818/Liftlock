package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

/** The producer intentionally cannot draw until native control has been requested. */
public final class NativeExitSequenceTest {
    @Test public void oneSwipeCanUnlockBeforeItsFirstFrameWithoutReleasingCoverage() {
        var scene = new ScenePresentation();
        var input = new DismissalHandoff();
        var requests = new SceneRequestOrder();
        scene.open(100); input.commit(7, false);
        assertFalse(scene.ready());
        assertTrue(input.consumeNativeRequest(7, 0));
        assertTrue(requests.begin(100, 7));
        assertTrue(scene.prepareNativeExit(100));
        assertFalse(scene.ready());
        assertFalse(scene.nativeOwned(true, true));
        // Native unlock now creates a Surface and sends its first nonempty draw.
        long binding = scene.bind(100, 42, 19, 8);
        assertTrue(scene.handoff());
        assertFalse(scene.drawn(binding, Integer.MAX_VALUE, true));
        assertFalse(scene.drawn(binding, 8, false));
        assertTrue(scene.drawn(binding, 8, true));
        assertFalse(scene.presented(binding, 8, true, false));
        assertFalse(scene.ready());
        assertTrue(scene.presented(binding, 8, true, true));
        assertFalse(scene.nativeOwned(true, true));
        scene.foregroundHidden(binding);
        assertFalse(scene.nativeOwned(true, false));
        assertTrue(scene.nativeOwned(true, true));
        assertFalse(input.consumeNativeRequest(7, 0));
        assertFalse(requests.begin(100, 7));
    }

    @Test public void retouchAndReplacementRejectEveryOldUnlockReceipt() {
        var scene = new ScenePresentation();
        var requests = new SceneRequestOrder();
        scene.open(100); requests.begin(100, 7); scene.prepareNativeExit(100);
        long old = scene.bind(100, 42, 19, 8);
        scene.drawn(old, 8, true);
        assertTrue(requests.end(100, 7));
        assertTrue(scene.cancelNativeExit(100));
        assertFalse(requests.begin(100, 7));
        assertTrue(requests.begin(100, 8));
        assertTrue(scene.prepareNativeExit(100));
        long current = scene.bind(100, 43, 20, 9);
        assertFalse(requests.end(100, 7));
        assertFalse(scene.presented(old, 8, true, true));
        assertFalse(scene.ready());
        assertTrue(scene.drawn(current, 9, true));
        assertTrue(scene.presented(current, 9, true, true));
        scene.foregroundHidden(old);
        assertFalse(scene.nativeOwned(true, true));
        scene.foregroundHidden(current);
        assertTrue(scene.nativeOwned(true, true));
    }
}
