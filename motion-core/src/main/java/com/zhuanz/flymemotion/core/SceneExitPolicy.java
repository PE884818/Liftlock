package com.zhuanz.flymemotion.core;

/** Releasing a drawing lease is different from undoing an accepted native unlock. */
public final class SceneExitPolicy {
    private SceneExitPolicy() {}
    public static boolean handoff(boolean forceCancel, boolean authenticated, boolean safeTarget,
                                  boolean nativeLocked, boolean nativeGoingAway) {
        return !forceCancel && authenticated && safeTarget && (nativeGoingAway || !nativeLocked);
    }
    /** A completed native exit cannot be undone by a later lease cancel or stale client flag. */
    public static boolean completedNativeExit(boolean confirmed, boolean showing, boolean locked, boolean explicitCancellation) {
        return confirmed && !showing && !locked && !explicitCancellation;
    }
    /** HOME's native onResume consumes its one-shot icon entry animation. */
    public static boolean activateRenderer(boolean home, boolean revealing) { return !home || revealing; }
    /**
     * Whether SystemUI may ask for that early RESUME. With late desktop animation, only a
     * confirmed APP return may; HOME (or a target not yet named) waits for the native unlock.
     */
    public static boolean requestEarlyResume(boolean lateDesktopAnimation, String target) {
        return !lateDesktopAnimation || "APP".equals(target);
    }
    /** Clearing the transient going-away flag must not reinstate the locked resume gate. */
    public static boolean deferNativeResume(boolean showing, boolean locked, boolean goingAway, boolean explicitActivation) {
        return showing && locked && !goingAway && !explicitActivation;
    }
    /** Native lease retirement is distinct from a pre-unlock preview's new-frame proof. */
    public static boolean nativeTakeover(boolean handoff, boolean showing, boolean locked, boolean goingAway,
            boolean foregroundHidden, boolean resumed, boolean focused, boolean drawn, boolean nativeVisible, boolean parents) {
        return handoff && !showing && !locked && !goingAway && foregroundHidden && resumed && focused && drawn && nativeVisible && parents;
    }
}
