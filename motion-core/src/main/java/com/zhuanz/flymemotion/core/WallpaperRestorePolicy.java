package com.zhuanz.flymemotion.core;

/** A successful native unlock must not re-expose its retired LOCK producer. */
public final class WallpaperRestorePolicy {
    private WallpaperRestorePolicy() {}
    public static boolean sourceVisible(boolean handoff, boolean requested, boolean clientVisible) {
        // Cancellation keeps the native fade's still-visible producer until native hide.
        return !handoff && (requested || clientVisible);
    }
    public static boolean commandVisible(boolean exitHeld, boolean wanted) { return !exitHeld && wanted; }
}
