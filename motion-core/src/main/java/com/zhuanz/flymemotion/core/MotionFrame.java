package com.zhuanz.flymemotion.core;

/** One pixel-space sample shared by content, wallpaper and native blur. */
public record MotionFrame(float rawHeight, float velocity, float progress, float springHeight,
        float contentY, float wallpaperHeight, float wallpaperAlpha, float blurProgress,
        float nativeHeight, float fullHeight, boolean authenticationRequired, float wallpaperProgress) {
    /** Independent native blur sheet: its screen bottom follows the computed coverage. */
    public float blurHeight() {
        return authenticationRequired ? fullHeight : FlymeMotion.clamp(fullHeight + contentY, 0, fullHeight);
    }
    public float blurY() { return blurHeight() - fullHeight; }

    /** Cover the screen-space bottom of the moving content, including spring/native offsets. */
    public MotionFrame coveringContent(float actualContentY) {
        if (!Float.isFinite(actualContentY)) throw new IllegalArgumentException("Invalid content pose");
        if (authenticationRequired) return this;
        float covered = FlymeMotion.clamp(Math.max(wallpaperHeight, fullHeight + actualContentY), 0, fullHeight);
        if (covered == wallpaperHeight) return this;
        return new MotionFrame(rawHeight, velocity, progress, springHeight, contentY,
                covered, wallpaperAlpha, blurProgress, nativeHeight, fullHeight, false, wallpaperProgress);
    }
    public static MotionFrame sample(float raw, float velocity, float height, boolean canDismiss, float blurStrength) {
        return sample(raw, velocity, height, height, canDismiss, blurStrength);
    }
    public static MotionFrame sample(float raw, float velocity, float height, float displayHeight, boolean canDismiss, float blurStrength) {
        if (!Float.isFinite(raw) || !Float.isFinite(velocity) || !Float.isFinite(height) || height <= 0)
            throw new IllegalArgumentException("Invalid motion sample");
        float bounded = FlymeMotion.clamp(raw, 0, height), p = bounded / height;
        float spring = Math.max(0, raw - height);
        return new MotionFrame(raw, velocity, p, spring,
                -displayHeight * (1 - p) / (canDismiss ? 1 : 8) + spring,
                canDismiss ? displayHeight * p : displayHeight,
                canDismiss ? FlymeMotion.clamp((p - .02f) / .78f, 0, 1) : 1,
                canDismiss && p > 0 ? FlymeMotion.clamp((1 - p) / .3f, 0, 1) * blurStrength : 0,
                bounded, displayHeight, !canDismiss, canDismiss ? p : 1);
    }

    /** Readiness may hold wallpaper coverage, but must never change the finger's content pose. */
    public MotionFrame withWallpaperProgress(float coverage) {
        if (!Float.isFinite(coverage)) throw new IllegalArgumentException("Invalid wallpaper progress");
        if (authenticationRequired) return this;
        float p = FlymeMotion.clamp(coverage, 0, 1);
        return new MotionFrame(rawHeight, velocity, progress, springHeight, contentY,
                fullHeight * p, FlymeMotion.clamp((p - .02f) / .78f, 0, 1), blurProgress,
                nativeHeight, fullHeight, false, p);
    }

    /** Present one sheet edge: readiness cannot make the wallpaper lag behind its controls. */
    public MotionFrame withPresentationProgress(float coverage, float blurStrength) {
        if (!Float.isFinite(coverage)) throw new IllegalArgumentException("Invalid presentation progress");
        if (authenticationRequired) return this;
        float p = FlymeMotion.clamp(coverage, 0, 1);
        return new MotionFrame(rawHeight, velocity, p, springHeight,
                -fullHeight * (1 - p) + springHeight, fullHeight * p,
                FlymeMotion.clamp((p - .02f) / .78f, 0, 1),
                p > 0 ? FlymeMotion.nativeBlurRatio(p, blurStrength) : 0,
                nativeHeight, fullHeight, false, p).coveringContent(-fullHeight * (1 - p) + springHeight);
    }
}
