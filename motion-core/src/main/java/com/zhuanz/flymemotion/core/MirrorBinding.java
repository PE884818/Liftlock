package com.zhuanz.flymemotion.core;

/** A commit receipt may authorize detaching only the exact source generation it displayed. */
public final class MirrorBinding {
    private long session = -1, generation = -1, layer = -1;
    private boolean committed, detached;
    public void begin(long session, long generation, long layer) {
        if (session < 0 || generation < 0 || layer < 0) throw new IllegalArgumentException("Invalid mirror identity");
        this.session = session; this.generation = generation; this.layer = layer;
        committed = detached = false;
    }
    public boolean acknowledge(long session, long generation, long layer) {
        if (this.session != session || this.generation != generation || this.layer != layer || this.session < 0) return false;
        committed = true; return true;
    }
    public boolean canDetach(boolean authenticated, boolean sourceDrawn) { return committed && authenticated && sourceDrawn; }
    public void detached() { if (!committed) throw new IllegalStateException("Mirror is not committed"); detached = true; }
    public boolean ready() { return committed && detached; }
    public boolean committed() { return committed; }
    public long generation() { return generation; }
    public void clear() { session = generation = layer = -1; committed = detached = false; }
}
