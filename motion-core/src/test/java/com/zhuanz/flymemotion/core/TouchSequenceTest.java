package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TouchSequenceTest {
    @Test public void interceptedDownAndDeliveredDownPinOnlyOnce() {
        TouchSequence input = new TouchSequence(); GestureSession session = new GestureSession();
        if (input.begin(10, 100, 900)) session.begin();
        long token = session.generation();
        session.update(MotionConfig.defaults().withEnabled(false));
        if (input.begin(10, 100, 600)) session.begin();
        assertEquals(token, session.generation()); assertTrue(session.config().enabled);
        assertTrue(input.upward(100, 870, 12));
    }
    @Test public void moveAfterInterceptionUsesOriginalDownWithoutAnotherDown() {
        TouchSequence input = new TouchSequence(); input.begin(11, 100, 900);
        assertTrue(input.matches(11)); assertTrue(input.upward(101, 800, 12));
        assertFalse(input.upward(250, 800, 12));
    }
    @Test public void screenSpaceDirectionDoesNotChangeWhenParentMoves() {
        TouchSequence input = new TouchSequence(); input.begin(12, 100, 900);
        // The view-local Y can increase as its parent moves; raw screen Y still decreases.
        assertTrue(input.upward(100, 700, 12));
    }
    @Test public void retouchStartsBeforeOldCancellationAndKeepsNewSession() {
        TouchSequence input = new TouchSequence(); GestureSession session = new GestureSession();
        input.begin(13, 100, 900); long old = session.begin();
        assertTrue(input.begin(14, 100, 700)); long next = session.begin();
        session.finish(old); assertTrue(session.active()); assertEquals(next, session.generation());
        assertFalse(input.begin(14, 100, 700));
    }
    @Test public void cancelledSequenceCannotReuseItsDirection() {
        TouchSequence input = new TouchSequence(); input.begin(15, 100, 900); input.reset();
        assertFalse(input.matches(15)); assertTrue(input.begin(16, 200, 700));
        assertFalse(input.upward(200, 720, 12));
    }
}
