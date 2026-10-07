package com.zhuanz.flymemotion.core;

/** One release policy, independent of the number of checks in the OEM release dispatcher. */
public final class LockscreenRelease {
    private LockscreenRelease() {}
    public record Input(float progress, float velocityY, float velocityX, float density,
                        float displacementY, boolean canDismiss) {}
    public record Decision(boolean expand, boolean falseTouch, int decisionType, int flingType) {}
    public interface Falsing {
        boolean isFalseTouch(int interactionType);
    }
    /** Native endMotionEvent also accepts an UP past slop even when no MOVE established tracking. */
    public static boolean upwardReleasePastSlop(float dx, float dy, float slop) {
        return Float.isFinite(dx) && Float.isFinite(dy) && Float.isFinite(slop) && slop >= 0
                && dy < -slop && Math.abs(dy) > Math.abs(dx);
    }
    public static Decision decide(Input input, MotionConfig config, Falsing falsing) {
        if (!Float.isFinite(input.progress) || !Float.isFinite(input.velocityY)
                || !Float.isFinite(input.velocityX) || !Float.isFinite(input.density)
                || input.density <= 0 || !Float.isFinite(input.displacementY))
            throw new IllegalArgumentException("Invalid native release snapshot");
        // The interaction types are taken from this target's endMotionEvent DEX.
        int decisionType = input.displacementY > 0 ? 0 : input.canDismiss ? 4 : 8;
        int flingType = input.velocityY == 0 ? 7 : decisionType;
        boolean decisionFalse = falsing.isFalseTouch(decisionType);
        boolean flingFalse = falsing.isFalseTouch(flingType);
        boolean rejected = decisionFalse || flingFalse;
        return new Decision(rejected || FlymeMotion.expand(input.progress, input.velocityY,
                input.velocityX, input.density, config), rejected, decisionType, flingType);
    }
}
