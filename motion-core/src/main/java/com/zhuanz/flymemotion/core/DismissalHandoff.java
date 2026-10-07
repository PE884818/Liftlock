package com.zhuanz.flymemotion.core;

/** An accepted collapse requests native authentication once, after settling; never on cancellation. */
public final class DismissalHandoff {
    private long pending = -1;
    private long presented = -1;
    public void commit(long token, boolean expand) { pending = expand ? -1 : token; presented = -1; }
    public void presented(long token) { if (pending == token) presented = token; }
    public void cancel(long token) { if (pending == token) { pending = -1; presented = -1; } }
    public void reset() { pending = presented = -1; }
    public boolean consume(long token, float progress) {
        if (pending != token || presented != token || !Float.isFinite(progress) || progress > .001f || progress < 0) return false;
        pending = presented = -1; return true;
    }
    /** Request native control after a settled accepted swipe. Visual release remains independent. */
    public boolean consumeNativeRequest(long token,float progress) {
        if(pending!=token || !Float.isFinite(progress) || progress<0 || progress>.001f)return false;
        pending=presented=-1;return true;
    }
}
