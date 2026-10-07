package com.zhuanz.flymemotion.core;

/** Reject duplicates/reordering within a session, including a late restored receipt. */
public final class SceneStatusOrder {
    private long session = -1, sequence = -1;
    public void begin(long id) { session = id; sequence = -1; }
    public boolean accept(long id, long next) {
        if (id != session || next < 0 || next <= sequence) return false;
        sequence = next; return true;
    }
}
