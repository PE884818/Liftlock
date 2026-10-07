package com.zhuanz.flymemotion.core;

/** All eligibility is obtained from native state; authentication is never supplied by the manager. */
public final class MotionPolicy {
    public enum Surface { LOCKSCREEN, NOTIFICATIONS, CONTROL_CENTER, AOD, BOUNCER, EDITOR, OCCLUDED }
    private MotionPolicy() {}
    public static boolean permits(MotionConfig config, Surface surface, boolean compatible, boolean failed) {
        if (!compatible || failed || !config.enabled) return false;
        return surface == Surface.LOCKSCREEN;
    }
}
