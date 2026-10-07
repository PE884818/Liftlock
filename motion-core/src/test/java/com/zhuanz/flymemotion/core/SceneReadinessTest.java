package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.zhuanz.flymemotion.core.SceneReadiness.Target.*;

public final class SceneReadinessTest {
    @Test public void appNeverUsesWallpaperAsSubstituteForWindowDraw() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, false, true, true, "", 4, 1, 2, true).ready());
        assertTrue(new SceneReadiness(1, APP, 1, true, true, true, true, false, true, "", 4, 1, 2, true).ready());
    }
    @Test public void homeRequiresActualHomeEngineAndWindow() {
        assertFalse(new SceneReadiness(1, HOME, 1, true, true, true, true, false, true, "", 4, 1, 2, true).ready());
        assertTrue(new SceneReadiness(1, HOME, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void hiddenAncestorIsNotReadyEvenWithShownWindowAndBuffer() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, false, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void noAuthenticationNeverRevealsAnApp() {
        assertFalse(new SceneReadiness(1, APP, 1, false, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void rebuiltSurfaceMustHaveFreshGeneration() {
        assertFalse(new SceneReadiness(1, APP, 0, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
        assertFalse(new SceneReadiness(1, APP, 3, true, true, true, true, true, false, "", 4, 1, 2, true).ready());
    }
    @Test public void endedAndUnselectedSessionsNeverReady() {
        assertFalse(new SceneReadiness(-1, APP, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
        assertFalse(new SceneReadiness(1, NONE, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void historicalDrawAndResumeCannotReplacePresentedFrame() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, true, true, true, "", 4, 1, 2, false).ready());
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, true, true, true, "", 4, 0, 2, true).ready());
    }
}
