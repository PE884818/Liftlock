package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class DesktopAnimationGateTest {
    @Test public void defaultDoesNotChangeNativeExit(){var g=new DesktopAnimationGate();g.begin(1,false);assertFalse(g.accepts(1,true));g.prepared(1);g.play(1);g.released(1);assertFalse(g.consume(1));}
    @Test public void applicationsNeverDefer(){var g=new DesktopAnimationGate();g.begin(1,true);assertFalse(g.accepts(1,false));assertTrue(g.accepts(1,true));}
    @Test public void previewPreparationDoesNotUnlockOrPlay(){var g=new DesktopAnimationGate();g.begin(1,true);g.prepared(1);g.play(1);assertFalse(g.consume(1));}
    @Test public void nativeHandoffPlaysOnce(){var g=new DesktopAnimationGate();g.begin(1,true);g.prepared(1);g.play(1);g.released(1);assertTrue(g.consume(1));assertFalse(g.consume(1));assertFalse(g.accepts(1,true));}
    @Test public void latePlayAfterHandoffStillUsesThePreparedRequest(){var g=new DesktopAnimationGate();g.begin(1,true);g.prepared(1);g.released(1);assertFalse(g.consume(1));assertTrue(g.accepts(1,true));g.play(1);assertTrue(g.consume(1));}
    @Test public void oldReleaseCannotStartNewGesture(){var g=new DesktopAnimationGate();g.begin(1,true);g.prepared(1);g.play(1);g.begin(2,true);g.prepared(2);g.play(2);g.released(1);assertFalse(g.consume(2));g.released(2);assertTrue(g.consume(2));}
    @Test public void cancelAndTimeoutCannotReplay(){var g=new DesktopAnimationGate();g.begin(1,true);g.prepared(1);g.play(1);g.cancel();g.released(1);assertFalse(g.consume(1));assertFalse(g.accepts(1,true));}
    @Test public void missingNativePrepareDoesNotHideDesktop(){var g=new DesktopAnimationGate();g.begin(1,true);g.play(1);g.released(1);assertFalse(g.consume(1));}
    @Test public void preferencesPreserveChoiceAndPinPerGesture(){MotionConfig c=MotionConfig.defaults().withLateDesktopAnimation(true);assertTrue(c.withEnabled(false).lateDesktopAnimation);assertTrue(c.withBottomRegion(BottomGestureRegion.defaults()).lateDesktopAnimation);var s=new GestureSession();s.update(c);long t=s.begin();assertTrue(s.config().lateDesktopAnimation);s.update(c.withLateDesktopAnimation(false));assertTrue(s.config().lateDesktopAnimation);s.finish(t);assertFalse(s.config().lateDesktopAnimation);}
    @Test public void newChoiceCannotEnableNotificationHooks(){assertFalse(MotionPolicy.permits(MotionConfig.defaults().withLateDesktopAnimation(true),MotionPolicy.Surface.NOTIFICATIONS,true,false));}
}
