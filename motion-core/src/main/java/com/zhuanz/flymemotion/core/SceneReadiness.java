package com.zhuanz.flymemotion.core;

/** Target-specific quorum. Wallpaper drawing alone never proves an app return scene. */
public record SceneReadiness(long session, Target target, long surfaceGeneration,
        boolean authenticated, boolean windowVisible, boolean ancestorsVisible, boolean windowDrawn,
        boolean wallpaperDrawn, boolean surfaceValid, String reason, long windowIdentity,
        long bindingEpoch, long drawSequence, boolean framePresented) {
    public enum Target { NONE, HOME, APP }
    public boolean ready() {
        return session >= 0 && surfaceGeneration > 0 && authenticated && target != Target.NONE
                && windowIdentity > 0 && bindingEpoch > 0 && drawSequence >= 0 && framePresented
                && surfaceValid && windowVisible && ancestorsVisible && windowDrawn
                && (target != Target.HOME || wallpaperDrawn);
    }
}
