package com.zhuanz.flymemotion.core;

/** A covered, authenticated return target may draw actively while the input shade keeps focus. */
public final class SceneActivation {
    private SceneActivation() {}
    public static boolean permitted(boolean liveLease, boolean authenticated, boolean fullCoverCommitted,
                                    boolean inputOwnedByShade, boolean safeTarget) {
        return liveLease && authenticated && fullCoverCommitted && inputOwnedByShade && safeTarget;
    }
    public static boolean pauseOnRelease(boolean ownedResume, boolean stillResumed, boolean keyguardShowing,
                                         boolean nativeExitInProgress) {
        return ownedResume && stillResumed && keyguardShowing && !nativeExitInProgress;
    }
}
