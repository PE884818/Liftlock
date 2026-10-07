package com.zhuanz.flymemotion.core;

/** Formulas verified against the supplied Flyme 12.6 SystemUI, adapted to native blur ratios. */
public final class FlymeMotion {
    private FlymeMotion() {}
    public static float clamp(float n, float lo, float hi) { return Math.max(lo, Math.min(hi, n)); }
    public static float progress(float height, float fullHeight) {
        return Float.isFinite(height) && Float.isFinite(fullHeight) && fullHeight > 0
                ? clamp(height / fullHeight, 0, 1) : 1;
    }
    public static float keyguardY(float p, float height, boolean dismissible) {
        return -height * (1 - clamp(p, 0, 1)) / (dismissible ? 1 : 8);
    }
    public static float nativeBlurRatio(float p, float strength) {
        // Flyme backgroundBlurBg maps p=1..0.7 to radius=0..80. Keep only its ratio;
        // HyperOS owns the actual kernel, radius, material, blending and availability.
        return clamp((1 - p) / .3f, 0, 1) * clamp(strength, 0, 1);
    }
    public static boolean expand(float p, float vy, float vx, float density, MotionConfig config) {
        float minimum = config.minimumVelocityDp * Math.max(.1f, density);
        if (Math.abs(vy) < minimum) return p > config.threshold;
        float speed = (float) Math.hypot(vx, vy);
        return vy >= 0 || (speed > 0 && Math.abs(vy / speed) < .5f);
    }
    public static float damping(float p) { return (float) Math.sin(clamp(p, 0, 1) * Math.PI / 2); }
}
