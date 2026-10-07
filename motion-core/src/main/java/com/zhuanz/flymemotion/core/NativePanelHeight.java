package com.zhuanz.flymemotion.core;

/** Prevent OEM's positive subpixel height from ending a closing spring before its final frame. */
public final class NativePanelHeight {
    private NativePanelHeight() {}
    public static float sample(float raw, float full, boolean closingSpring, boolean finalSample) {
        float height = FlymeMotion.clamp(raw, 0, full);
        return closingSpring && !finalSample && full >= 2 ? Math.max(2, height) : height;
    }
}
