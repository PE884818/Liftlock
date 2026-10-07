package com.zhuanz.flymemotion.core;

/** One screen-space origin across intercept/touch delivery and moving view coordinates. */
public final class TouchSequence {
    private long downTime = Long.MIN_VALUE;
    private float x, y;
    public boolean begin(long eventDownTime, float screenX, float screenY) {
        if (downTime == eventDownTime) return false;
        downTime = eventDownTime; x = screenX; y = screenY; return true;
    }
    public boolean matches(long eventDownTime) { return downTime == eventDownTime; }
    public long downTime() { return downTime; }
    public boolean upward(float screenX, float screenY, float slop) {
        float dx = screenX - x, dy = screenY - y;
        return dy < -slop && -dy > Math.abs(dx);
    }
    public void reset() { downTime = Long.MIN_VALUE; }
}
