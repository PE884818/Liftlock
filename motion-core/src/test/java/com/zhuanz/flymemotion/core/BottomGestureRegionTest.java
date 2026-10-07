package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class BottomGestureRegionTest {
    @Test public void realScreenBoundsAndDisabledRegion() {
        BottomGestureRegion r = BottomGestureRegion.defaults();
        assertTrue(r.contains(0, 2608, 1200, 2608));
        assertTrue(r.contains(1199, 2300, 1200, 2608));
        assertFalse(r.contains(600, 2200, 1200, 2608));
        assertFalse(r.contains(-1, 2400, 1200, 2608));
        assertFalse(r.contains(600, 2609, 1200, 2608));
        assertFalse(new BottomGestureRegion(false,.15f,1,.5f).contains(600,2400,1200,2608));
    }
    @Test public void boundedHorizontalPositionAndInvalidGeometry() {
        BottomGestureRegion r = new BottomGestureRegion(true,.2f,.4f,1);
        assertEquals(.6f,r.left(),.00001f);assertEquals(1,r.right(),.00001f);
        assertTrue(r.contains(1000,2400,1200,2608));assertFalse(r.contains(500,2400,1200,2608));
        assertFalse(r.contains(Float.NaN,2400,1200,2608));assertFalse(r.contains(1000,2400,0,2608));
        r = new BottomGestureRegion(true,Float.NaN,Float.NaN,Float.NaN);
        assertEquals(.15f,r.height(),0);assertEquals(1,r.width(),0);assertEquals(.5f,r.center(),0);
    }
    @Test public void configurationRemainsPinnedUntilGestureEnd() {
        GestureSession session = new GestureSession();
        MotionConfig c = MotionConfig.defaults();session.update(c);long token=session.beginInput();
        session.update(c.withBottomRegion(new BottomGestureRegion(true,.3f,.5f,.7f)));
        assertEquals(.15f,session.config().bottomRegion.height(),0);
        session.endInput(token);session.finish(token);
        assertEquals(.3f,session.config().bottomRegion.height(),0);
        assertEquals(.5f,c.withEnabled(false).bottomRegion.center(),0);
    }
}
