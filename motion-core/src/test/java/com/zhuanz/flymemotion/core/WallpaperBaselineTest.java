package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WallpaperBaselineTest {
    @Test public void coldBootWithoutAodCallbackCanBindWhenWmGeometryArrives() {
        var state = new WallpaperBaseline();
        assertNull(state.snapshotOrNull(40, -1, null));
        assertArrayEquals(new float[]{1.03f, 1.03f, -18, -39, 1},
                state.snapshotOrNull(40, -1, null, 40, new float[]{1.03f, 1.03f, -18, -39, 1}), 0);
        assertNull(state.snapshotOrNull(40, -1, null));
    }
    @Test public void pendingNewLayerCannotUseTheOldNativeGeometry() {
        var state = new WallpaperBaseline(); state.observe(40, new float[]{1.05f, 1.05f, -34, -52, 1});
        assertNull(state.snapshotOrNull(41, -1, null));
        state.observe(41, new float[]{1, 1, 0, 0, 1});
        assertEquals(1, state.snapshotOrNull(41, -1, null)[0], 0);
    }
    @Test public void wmFallbackFollowsLatestGeometryInsteadOfBecomingPermanentObservation() {
        var state = new WallpaperBaseline();
        assertEquals(-39, state.snapshotOrNull(40, -1, null, 40, new float[]{1, 1, -18, -39, 1})[3], 0);
        assertEquals(-25, state.snapshotOrNull(40, -1, null, 40, new float[]{1, 1, -18, -25, 1})[3], 0);
        assertNull(state.snapshotOrNull(40, -1, null, 41, new float[]{1, 1, 0, 0, 1}));
    }
    @Test public void actualWakeTransformHasPriorityOverWmGeometryFallback() {
        var state = new WallpaperBaseline(); state.observe(40, new float[]{1.05f, 1.05f, -34, -52, 1});
        assertEquals(1.05f, state.snapshotOrNull(40, -1, null, 40, new float[]{1, 1, 0, 0, 1})[0], 0);
    }
    @Test public void clearingViewRootDoesNotInventIdentityZoomAtFirstDrag() {
        var state = new WallpaperBaseline();
        state.observe(40, new float[]{1.05f, 1.05f, -34, -52, 1});
        state.observe(-1, null); // CustomValueAnim clears the VRI reference after wake completes.
        assertArrayEquals(new float[]{1.05f, 1.05f, -34, -52, 1}, state.snapshot(40, -1, null), 0);
    }
    @Test public void rootMatrixCanOnlySupplyBaselineForSameSurface() {
        var state = new WallpaperBaseline();
        state.observe(40, new float[]{1.05f, 1.05f, -34, -52, 1});
        assertArrayEquals(new float[]{1.1f, 1.1f, -10, -20, .8f},
                state.snapshot(40, 40, new float[]{1.1f, 1.1f, -10, -20, .8f}), 0);
        assertEquals(1.05f, state.snapshot(40, 41, new float[]{1, 1, 0, 0, 1})[0], 0);
    }
    @Test public void surfaceRecreatedDoesNotReuseOldLayerTransform() {
        var state = new WallpaperBaseline(); state.observe(40, new float[]{1.05f, 1.05f, -34, -52, 1});
        assertThrows(IllegalStateException.class, () -> state.snapshot(41, -1, null));
        state.observe(41, new float[]{1, 1, 0, 0, 1});
        assertEquals(1, state.snapshot(41, -1, null)[0], 0);
    }
    @Test public void nativeArraysAndReturnedSnapshotsCannotMutateHeldBaseline() {
        var state = new WallpaperBaseline(); float[] value = {1.05f, 1.05f, -34, -52, 1};
        state.observe(40, value); value[0] = 3;
        float[] snapshot = state.snapshot(40, -1, null); snapshot[0] = 2;
        assertEquals(1.05f, state.snapshot(40, -1, null)[0], 0);
    }
    @Test public void unavailableOrInvalidBaselineFailsBeforeChangingScale() {
        var state = new WallpaperBaseline();
        assertThrows(IllegalStateException.class, () -> state.snapshot(40, -1, null));
        assertThrows(IllegalArgumentException.class, () -> state.observe(40, new float[]{0, 1, 0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> state.observe(40, new float[]{1, Float.NaN, 0, 0, 1}));
    }
}
