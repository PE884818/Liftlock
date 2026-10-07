package com.zhuanz.flymemotion.core;

/** Input motion stays immediate; scene readiness controls only safe wallpaper exposure. */
public final class RevealGate {
    private float height;
    private long last;
    public void reset(float full, long now) { height = full; last = now; }
    public void reset(float full, long now, boolean prepared) { reset(full, now); }
    /** Preserve the input/physics pose even while the opaque wallpaper must cover a pending scene. */
    public MotionFrame compose(MotionFrame motion, float nativeDistance, boolean sceneReady, long now) {
        return compose(motion, nativeDistance, sceneReady, now, 1);
    }
    public MotionFrame compose(MotionFrame motion, float nativeDistance, boolean sceneReady, long now, float blurStrength) {
        if (motion.authenticationRequired()) return motion;
        float coverage = sample(motion.rawHeight(), nativeDistance, sceneReady, now);
        return motion.withWallpaperProgress(FlymeMotion.progress(coverage, nativeDistance)).coveringContent(motion.contentY());
    }
    public float sample(float wanted, float full, boolean ready, long now) {
        if (now < last) return height;
        if (!ready) {
            // Losing the current presentation proof requires FULL opaque protection.
            // Keeping a partial/faded cover still exposes a blank replacement window.
            height = full; last = now;
            return height;
        }
        // Readiness controls whether the backdrop can reveal, never a second motion curve.
        height = wanted; last = now; return wanted;
    }
}
