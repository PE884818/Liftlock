package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WindowGestureTest {
    @Test public void childCancelDuringDownCannotFinishRetouchedFinger() {
        GestureSession session = new GestureSession(); TouchDispatch dispatch = new TouchDispatch();
        long old = session.beginInput(); session.endInput(old);
        var down = dispatch.enter(TouchDispatch.DOWN, 20, 20);
        long next = session.beginInput();
        assertEquals("child-cancel", dispatch.origin(TouchDispatch.CANCEL, 20));
        assertFalse(dispatch.matches(TouchDispatch.CANCEL, 20));
        assertFalse(session.finish(old)); assertFalse(session.finish(next));
        dispatch.exit(down);
        var up = dispatch.enter(TouchDispatch.UP, 20, 50);
        assertTrue(dispatch.matches(TouchDispatch.UP, 20));
        assertTrue(session.touching()); assertEquals(next, session.generation());
        session.endInput(next); assertTrue(session.finish(next)); dispatch.exit(up);
    }
    @Test public void childCancelDuringMoveCannotEndGlobalInput() {
        var dispatch = new TouchDispatch(); var frame = dispatch.enter(TouchDispatch.MOVE, 30, 40);
        assertEquals("child-cancel", dispatch.origin(TouchDispatch.CANCEL, 30));
        assertFalse(dispatch.matches(TouchDispatch.CANCEL, 30));
        assertTrue(dispatch.matches(TouchDispatch.MOVE, 30)); dispatch.exit(frame);
    }
    @Test public void genuineWindowCancelIsAuthoritative() {
        var dispatch = new TouchDispatch(); var session = new GestureSession(); long token = session.beginInput();
        var frame = dispatch.enter(TouchDispatch.CANCEL, 40, 70);
        assertTrue(frame.terminal()); assertTrue(dispatch.matches(TouchDispatch.CANCEL, 40));
        session.endInput(token); assertTrue(session.finish(token)); assertFalse(session.touching());
        dispatch.exit(frame);
    }
    @Test public void nativeNestedWindowCancelCanUseNewDownTime() {
        var dispatch = new TouchDispatch(); var session = new GestureSession(); long token = session.beginInput();
        var pointerDown = dispatch.enter(5, 50, 100);
        var cancel = dispatch.enter(TouchDispatch.CANCEL, 101, 101);
        assertTrue(cancel.terminal()); assertFalse(cancel.outermost());
        session.endInput(token); assertTrue(session.finish(token)); dispatch.exit(cancel);
        assertSame(pointerDown, dispatch.current()); assertFalse(session.touching()); dispatch.exit(pointerDown);
    }
    @Test public void staleInputCannotCommitCurrentRelease() {
        var dispatch = new TouchDispatch(); var up = dispatch.enter(TouchDispatch.UP, 60, 90);
        assertFalse(dispatch.matches(TouchDispatch.UP, 59));
        assertEquals("stale-stream", dispatch.origin(TouchDispatch.UP, 59)); dispatch.exit(up);
        assertFalse(dispatch.matches(TouchDispatch.UP, 60));
    }
    @Test public void nestedDispatchAndExceptionRestoreParentContext() {
        var dispatch = new TouchDispatch(); var down = dispatch.enter(TouchDispatch.DOWN, 70, 70);
        var nested = dispatch.enter(TouchDispatch.UP, 70, 70);
        try { assertFalse(dispatch.matches(TouchDispatch.UP, 70)); throw new IllegalStateException("native failure"); }
        catch (IllegalStateException expected) {}
        finally { dispatch.exit(nested); }
        assertTrue(dispatch.matches(TouchDispatch.DOWN, 70)); dispatch.exit(down); assertNull(dispatch.current());
    }
    @Test public void animatorEndingInsideReleaseCannotClearInputBeforeWindowFinishes() {
        var session = new GestureSession(); long token = session.beginInput();
        assertFalse(session.finish(token)); assertTrue(session.active()); assertTrue(session.touching());
        session.endInput(token); assertTrue(session.finish(token)); assertFalse(session.active());
    }
    @Test public void oldWindowTerminalCannotEndNewFinger() {
        var session = new GestureSession(); long old = session.beginInput(); long next = session.beginInput();
        session.endInput(old); assertTrue(session.touching()); assertFalse(session.finish(old));
        session.endInput(next); assertTrue(session.finish(next));
    }
    @Test public void disableInvalidatesAllInFlightInputAndAnimationTokens() {
        var session = new GestureSession(); long token = session.beginInput(); session.disable();
        assertFalse(session.touching()); assertFalse(session.active()); assertFalse(session.finish(token));
        session.endInput(token); assertFalse(session.touching()); assertFalse(session.config().enabled);
    }
    @Test public void childCancelKeepsParameterSnapshotUntilWindowEnds() {
        var session = new GestureSession(); long token = session.beginInput();
        session.update(MotionConfig.defaults().withEnabled(false));
        assertFalse(session.finish(token)); assertTrue(session.config().enabled);
        session.endInput(token); assertTrue(session.finish(token)); assertFalse(session.config().enabled);
    }
    @Test public void firstMoveChildCancelCannotStartNativeFlingBeforeOwnership() {
        var dispatch = new TouchDispatch(); var frame = dispatch.enter(TouchDispatch.MOVE, 30, 40);
        assertTrue(dispatch.suppressChildCancel(TouchDispatch.CANCEL, 30, 30, true, true));
        assertFalse(dispatch.matches(TouchDispatch.CANCEL, 30)); dispatch.exit(frame);
    }
    @Test public void retouchChildCancelKeepsNewFingerUntilItsActualUp() {
        var dispatch = new TouchDispatch();
        for (int action : new int[]{TouchDispatch.DOWN,TouchDispatch.MOVE,TouchDispatch.UP}) {
            var frame=dispatch.enter(action,60,80);
            assertTrue(dispatch.suppressChildCancel(TouchDispatch.CANCEL,60,60,true,true));
            assertFalse(dispatch.suppressChildCancel(action,60,60,true,true));dispatch.exit(frame);
        }
    }
    @Test public void actualWindowCancelStillRunsNativeRecovery() {
        var dispatch=new TouchDispatch();var frame=dispatch.enter(TouchDispatch.CANCEL,70,90);
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,70,70,true,true));dispatch.exit(frame);
    }
    @Test public void staleNonLockOrFinishedCancelsKeepNativeBehavior() {
        var dispatch=new TouchDispatch();var frame=dispatch.enter(TouchDispatch.MOVE,90,100);
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,89,90,true,true));
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,90,89,true,true));
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,90,90,false,true));
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,90,90,true,false));dispatch.exit(frame);
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,90,90,true,true));
    }
    @Test public void nestedActualCancelIsNeverMistakenForParentRouting() {
        var dispatch=new TouchDispatch();var move=dispatch.enter(TouchDispatch.MOVE,90,100);
        var cancel=dispatch.enter(TouchDispatch.CANCEL,101,101);
        assertFalse(dispatch.suppressChildCancel(TouchDispatch.CANCEL,101,101,true,true));
        dispatch.exit(cancel);dispatch.exit(move);
    }
}
