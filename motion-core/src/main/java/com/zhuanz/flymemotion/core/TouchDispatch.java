package com.zhuanz.flymemotion.core;

/** Immutable window action while ViewGroup temporarily changes the same event to child CANCEL. */
public final class TouchDispatch {
    public static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    public record Frame(int action, long downTime, long eventTime, Frame parent) {
        public boolean outermost() { return parent == null; }
        public boolean terminal() { return action == UP || action == CANCEL; }
    }
    private Frame current;
    public Frame enter(int action, long downTime, long eventTime) {
        current = new Frame(action, downTime, eventTime, current); return current;
    }
    public void exit(Frame frame) {
        if (frame != current) throw new IllegalStateException("Unbalanced window dispatch");
        current = frame.parent();
    }
    public Frame current() { return current; }
    public boolean matches(int action, long downTime) {
        return current != null && current.outermost()
                && current.action() == action && current.downTime() == downTime;
    }
    /** A child CANCEL is routing bookkeeping, not the end of the window-owned finger. */
    public boolean suppressChildCancel(int action, long downTime, long activeDownTime, boolean touching, boolean lockOwned) {
        return lockOwned && touching && downTime == activeDownTime && current != null
                && current.outermost() && current.downTime() == downTime
                && action == CANCEL && current.action() != CANCEL;
    }
    public String origin(int action, long downTime) {
        if (current == null) return "outside-window";
        if (current.downTime() != downTime) return "stale-stream";
        if (action == CANCEL && current.action() != CANCEL) return "child-cancel";
        return current.outermost() ? "window" : "nested-window";
    }
}
