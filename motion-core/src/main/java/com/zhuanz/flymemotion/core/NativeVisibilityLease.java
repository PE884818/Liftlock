package com.zhuanz.flymemotion.core;

/** Borrow client visibility for drawing, then restore the latest native intent, including unlock. */
public final class NativeVisibilityLease {
    private boolean active, desired;
    public void begin(boolean nativeVisible) {
        if (active) throw new IllegalStateException("Visibility lease already active");
        active = true; desired = nativeVisible;
    }
    public boolean nativeChange(boolean wanted, boolean authorized) {
        if (!active) return wanted;
        desired = wanted;
        return authorized || wanted;
    }
    public boolean end() { active = false; return desired; }
    /** Native unlock takes ownership without replaying the pre-transition hidden intent. */
    public void handoff() { active = false; }
    public boolean active() { return active; }
}
