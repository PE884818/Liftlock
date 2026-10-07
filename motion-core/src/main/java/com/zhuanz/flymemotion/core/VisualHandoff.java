package com.zhuanz.flymemotion.core;

/** Decisions use the presented sheet and native visibility, not just logical unlock state. */
public final class VisualHandoff {
    private VisualHandoff() {}

    public static boolean settled(float nativeProgress, MotionFrame frame, boolean sceneReady,
            boolean engaged, boolean authenticationRequired) {
        if (!engaged || authenticationRequired) return true;
        if (!Float.isFinite(nativeProgress)) return false;
        if (nativeProgress >= .999f) {
            return frame.progress() >= .999f && frame.wallpaperProgress() >= .999f
                    && frame.wallpaperHeight() >= frame.fullHeight() - .5f
                    && frame.wallpaperAlpha() >= .999f && Math.abs(frame.contentY()) <= .5f
                    && Math.abs(frame.springHeight()) <= .5f;
        }
        return nativeProgress <= .001f && nativeProgress >= 0 && sceneReady
                && frame.progress() <= .001f && frame.wallpaperProgress() <= .001f
                && frame.wallpaperAlpha() == 0;
    }

    public static boolean nativeHidden(boolean showing, int statusBarState, boolean foregroundVisible) {
        // HyperOS first delivers showing(false) while statusBarState is still KEYGUARD and
        // updateKeyguardElementsVisibility() explicitly makes the depth foreground visible.
        return !showing && statusBarState == 0 && !foregroundVisible;
    }
}
