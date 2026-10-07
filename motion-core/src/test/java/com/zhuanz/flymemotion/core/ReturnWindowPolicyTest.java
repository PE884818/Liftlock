package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReturnWindowPolicyTest {
    @Test public void launcherSearchOverlayCannotSubstituteForLauncher() {
        assertFalse(ReturnWindowPolicy.main(1, 10144, 10151, 0));
        assertFalse(ReturnWindowPolicy.main(1, 10144, 10151, 1));
        assertTrue(ReturnWindowPolicy.main(1, 10151, 10151, 1));
    }
    @Test public void transparentSameUidOverlayAndStartingPreviewAreExcluded() {
        assertFalse(ReturnWindowPolicy.main(1, 10151, 10151, 0));
        assertFalse(ReturnWindowPolicy.main(3, 10151, 10151, 1));
        assertFalse(ReturnWindowPolicy.main(2, 10151, 10151, 1));
        assertFalse(ReturnWindowPolicy.main(1, 10151, 10151, Float.NaN));
    }
}
