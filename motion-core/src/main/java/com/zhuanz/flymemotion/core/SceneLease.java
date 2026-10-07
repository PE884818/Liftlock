package com.zhuanz.flymemotion.core;

/** Bounded scene ownership; stale messages cannot revive or close a newer scene. */
public final class SceneLease {
    public static final long TIMEOUT_MS = 1800;
    private long id = -1, latest = -1, until;
    public boolean begin(long next, long now) {
        if (next < 0 || next <= latest) return false;
        id = latest = next; until = now + TIMEOUT_MS; return true;
    }
    public boolean renew(long token, long now) {
        if (!active(now) || token != id) return false;
        until = now + TIMEOUT_MS; return true;
    }
    /** The caller must independently prove an authenticated, non-sleeping native handoff.
     * No frame is declared ready and no resources are released by this liveness renewal. */
    public void renewHandoff(long now) { if (id >= 0) until = now + TIMEOUT_MS; }
    public boolean end(long token) {
        if (token != id || id < 0) return false;
        clear(); return true;
    }
    public boolean active(long now) { return id >= 0 && now >= 0 && now < until; }
    public long id() { return id; }
    public void clear() { id = -1; until = 0; }
}
