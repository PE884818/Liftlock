package com.zhuanz.flymemotion.core;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SurfaceTopologyTest {
    @Test public void oldLiveDisplayAncestorsSurviveThousandsOfNewLayers() {
        var state = new SurfaceTopology();
        state.created(1, 0, true); state.created(4, 1, true); state.created(7, 4, true);
        for (int i = 20; i < 9000; i++) state.created(i, 7, true);
        state.created(9010, 7, true);
        state.retainLiveChains(Set.of(9010));
        assertEquals("none", state.blocker(9010));
        assertEquals(4, state.size()); assertNull(state.visibility(100));
    }
    @Test public void parentReplacementPreservesNewChainAndRetiresOldChain() {
        var state = new SurfaceTopology();
        state.created(1, 0, true); state.created(4, 1, true); state.created(7, 4, true);
        state.created(6999, 0, true); state.reparent(4, 6999);
        state.retainLiveChains(Set.of(7));
        assertEquals("none", state.blocker(7)); assertNull(state.visibility(1));
        assertEquals(Boolean.TRUE, state.visibility(6999));
    }
    @Test public void collectionNeverTurnsUnknownOrHiddenAncestorsIntoReady() {
        var state = new SurfaceTopology();
        state.created(63, 41, true); state.retainLiveChains(Set.of(63));
        assertEquals("unknown-visibility:41", state.blocker(63));
        state.created(41, 0, false); state.retainLiveChains(Set.of(63));
        assertEquals("hidden:41", state.blocker(63));
        state.visibility(41, true); assertEquals("none", state.blocker(63));
    }
    @Test public void cyclicOrMissingParentNeverBecomesReady() {
        var state = new SurfaceTopology(); state.visibility(63, true);
        assertEquals("unknown-parent:63", state.blocker(63));
        state.reparent(63, 41); state.created(41, 63, true);
        state.retainLiveChains(Set.of(63)); assertEquals("cycle:63", state.blocker(63));
    }
}
