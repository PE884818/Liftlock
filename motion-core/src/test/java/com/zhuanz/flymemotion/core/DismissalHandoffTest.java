package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DismissalHandoffTest {
    @Test public void nativeControlDoesNotDependOnTheFrameItWillProduce(){
        var gate=new DismissalHandoff();gate.commit(10,false);
        assertFalse(gate.consume(10,0));assertTrue(gate.consumeNativeRequest(10,0));
        assertFalse(gate.consumeNativeRequest(10,0));
    }
    @Test public void nativeControlRejectsCancelledExpandedAndUnsettledSwipes(){
        var gate=new DismissalHandoff();gate.commit(10,false);assertFalse(gate.consumeNativeRequest(10,.2f));
        assertFalse(gate.consumeNativeRequest(10,Float.NaN));assertFalse(gate.consumeNativeRequest(9,0));
        gate.cancel(10);assertFalse(gate.consumeNativeRequest(10,0));
        gate.commit(11,true);assertFalse(gate.consumeNativeRequest(11,0));
    }
    @Test public void latePresentedFrameCannotRearmConsumedNativeControl(){
        var gate=new DismissalHandoff();gate.commit(10,false);assertTrue(gate.consumeNativeRequest(10,0));
        gate.presented(10);assertFalse(gate.consume(10,0));assertFalse(gate.consumeNativeRequest(10,0));
    }
    @Test public void acceptedCollapseHandsOffOnceAtEnd() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false);
        assertFalse(handoff.consume(1, .4f)); assertFalse(handoff.consume(1, 0));
        handoff.presented(1); assertTrue(handoff.consume(1, 0)); assertFalse(handoff.consume(1, 0));
    }
    @Test public void reboundIncludingFalseTouchNeverRequestsAuthentication() {
        var handoff = new DismissalHandoff(); handoff.commit(1, true); assertFalse(handoff.consume(1, 0));
    }
    @Test public void cancelledAndRetouchedCollapseCannotDismissNewGesture() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false); handoff.cancel(1);
        assertFalse(handoff.consume(1, 0)); handoff.commit(2, false);
        assertFalse(handoff.consume(1, 0)); handoff.presented(2); assertTrue(handoff.consume(2, 0));
    }
    @Test public void disableOrNewDownClearsPendingIntent() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false); handoff.reset(); assertFalse(handoff.consume(1, 0));
    }
    @Test public void invalidHeightCannotRequestAuthentication() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false);
        handoff.presented(1);
        for (float progress : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 1}) assertFalse(handoff.consume(1, progress));
        assertTrue(handoff.consume(1, 0));
    }
    @Test public void anOldFrameCommitCannotAuthorizeANewRelease() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false); handoff.presented(1);
        handoff.commit(2, false); handoff.presented(1); assertFalse(handoff.consume(2, 0));
        handoff.presented(2); assertTrue(handoff.consume(2, 0));
    }
    @Test public void cancelledFrameCompletionCannotRearmAuthentication() {
        var handoff = new DismissalHandoff(); handoff.commit(1, false); handoff.cancel(1);
        handoff.presented(1); assertFalse(handoff.consume(1, 0));
    }
}
