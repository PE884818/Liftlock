package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class HomeUnlockGateTest {
    @Test public void defaultPassesNativeCallbacks(){var g=new HomeUnlockGate();g.arm(1,10234,false);assertFalse(g.hold(10234,false));}
    @Test public void onlySelectedHomeUidIsHeld(){var g=new HomeUnlockGate();g.arm(1,10234,true);assertTrue(g.hold(10234,false));assertFalse(g.hold(10235,false));assertFalse(g.hold(1000,false));}
    @Test public void lockNotificationsAlwaysPass(){var g=new HomeUnlockGate();g.arm(1,10234,true);assertFalse(g.hold(10234,true));}
    @Test public void appReturnDoesNotHoldAnyUid(){var g=new HomeUnlockGate();g.arm(1,-1,true);assertFalse(g.hold(10234,false));}
    @Test public void oldRestoreCannotReleaseNewSession(){var g=new HomeUnlockGate();g.arm(1,10234,true);g.arm(2,10234,true);assertFalse(g.finish(1));assertTrue(g.hold(10234,false));assertTrue(g.finish(2));assertFalse(g.hold(10234,false));}
    @Test public void renewAfterRestoreDoesNotRearm(){var g=new HomeUnlockGate();g.arm(1,10234,true);g.finish(1);g.arm(1,10234,true);assertFalse(g.hold(10234,false));}
    @Test public void lateNativeCallbackPassesAfterCommit(){var g=new HomeUnlockGate();g.arm(1,10234,true);assertTrue(g.finish(1));assertFalse(g.hold(10234,false));}
    @Test public void newLockCanArmItsOwnSession(){var g=new HomeUnlockGate();g.arm(1,10234,true);g.finish(1);g.arm(2,10234,true);assertTrue(g.hold(10234,false));}
    @Test public void cancellationAndSelectionChangePass(){var g=new HomeUnlockGate();g.arm(1,10234,true);g.cancel();assertFalse(g.hold(10234,false));g.arm(2,10234,true);g.arm(2,10234,false);assertFalse(g.hold(10234,false));}
}
