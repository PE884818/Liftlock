package com.zhuanz.flymemotion.core;

/** Screen-relative, bottom-anchored input region; pin it with the gesture configuration. */
public record BottomGestureRegion(boolean enabled, float height, float width, float center) {
    public BottomGestureRegion {
        height = MotionConfig.bounded(height, .05f, .4f, .15f);
        width = MotionConfig.bounded(width, .2f, 1, 1);
        center = MotionConfig.bounded(center, 0, 1, .5f);
    }
    public static BottomGestureRegion defaults() { return new BottomGestureRegion(true, .15f, 1, .5f); }
    public float left() { return FlymeMotion.clamp(center - width / 2, 0, 1 - width); }
    public float right() { return left() + width; }
    public boolean contains(float x, float y, float screenWidth, float screenHeight) {
        return enabled && Float.isFinite(x) && Float.isFinite(y) && screenWidth > 0 && screenHeight > 0
                && x >= screenWidth * left() && x <= screenWidth * right()
                && y >= screenHeight * (1 - height) && y <= screenHeight;
    }
}
