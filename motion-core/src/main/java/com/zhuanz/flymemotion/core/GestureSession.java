package com.zhuanz.flymemotion.core;

/** Gesture generations prevent a cancelled old animator from ending a newly retouched gesture. */
public final class GestureSession {
    private MotionConfig latest = MotionConfig.defaults(), pinned;
    private long generation;
    private boolean touching;
    public void update(MotionConfig config) { latest = config; }
    public long begin() { pinned = latest; return ++generation; }
    /** Only the window input stream may begin/end a finger, never a child receiver. */
    public long beginInput() { touching = true; return begin(); }
    public boolean touching() { return touching; }
    public void endInput(long token) { if (token == generation) touching = false; }
    public MotionConfig config() { return pinned == null ? latest : pinned; }
    public boolean active() { return pinned != null; }
    public long generation() { return generation; }
    public boolean finish(long token) {
        if (token != generation || touching) return false;
        pinned = null; return true;
    }
    public void disable() { latest = latest.withEnabled(false); pinned = null; touching = false; ++generation; }
}
