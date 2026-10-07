package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneExitPolicyTest {
    @Test public void completedExitCannotBecomeCancelButNewLockStillWins() {
        assertTrue(SceneExitPolicy.completedNativeExit(true,false,false,false));
        assertFalse(SceneExitPolicy.completedNativeExit(true,false,false,true));
        assertFalse(SceneExitPolicy.completedNativeExit(true,true,true,false));
        assertFalse(SceneExitPolicy.completedNativeExit(true,false,true,false));
        assertFalse(SceneExitPolicy.completedNativeExit(true,true,false,false));
        assertFalse(SceneExitPolicy.completedNativeExit(false,false,false,false));
    }
    @Test public void nativeResumeIsNeverBlockedAfterUnlockClearsGoingAway() {
        assertTrue(SceneExitPolicy.deferNativeResume(true,true,false,false));
        assertFalse(SceneExitPolicy.deferNativeResume(true,true,true,false));
        assertFalse(SceneExitPolicy.deferNativeResume(false,false,false,false));
        assertFalse(SceneExitPolicy.deferNativeResume(true,false,false,false));
        assertFalse(SceneExitPolicy.deferNativeResume(true,true,false,true));
        for (int flags=0;flags<16;flags++) {
            boolean showing=(flags&1)!=0, locked=(flags&2)!=0, away=(flags&4)!=0, explicit=(flags&8)!=0;
            assertEquals(showing&&locked&&!away&&!explicit, SceneExitPolicy.deferNativeResume(showing,locked,away,explicit));
        }
    }
    @Test public void nativeTakeoverRequiresEveryIndependentOwnershipCondition() {
        for (int flags=0;flags<1024;flags++) {
            boolean handoff=(flags&1)!=0, showing=(flags&2)!=0, locked=(flags&4)!=0, away=(flags&8)!=0;
            boolean hidden=(flags&16)!=0,resumed=(flags&32)!=0,focus=(flags&64)!=0,drawn=(flags&128)!=0;
            boolean visible=(flags&256)!=0,parents=(flags&512)!=0;
            assertEquals(flags==1009, SceneExitPolicy.nativeTakeover(handoff,showing,locked,away,hidden,resumed,focus,drawn,visible,parents));
        }
    }
    @Test public void nativeTakeoverDoesNotForgeAPreviewFence() {
        var scene=new ScenePresentation();scene.open(10);scene.prepareNativeExit(10);
        long binding=scene.bind(10,20,30,7);
        assertFalse(SceneExitPolicy.nativeTakeover(true,false,false,false,scene.foregroundHiddenCommitted(),true,true,true,true,true));
        scene.foregroundHidden(binding);
        assertTrue(SceneExitPolicy.nativeTakeover(true,false,false,false,scene.foregroundHiddenCommitted(),true,true,true,true,true));
        assertFalse(scene.ready());assertEquals("NONE",scene.proofSource());
        assertFalse(scene.nativeOwned(true,true));
    }
    @Test public void sleepAndUnqualifiedTargetsCannotUseUnlockHandoff() {
        for (int bits=0; bits<32; bits++) {
            boolean cancel=(bits&1)!=0, auth=(bits&2)!=0, safe=(bits&4)!=0;
            boolean locked=(bits&8)!=0, away=(bits&16)!=0;
            boolean actual=SceneExitPolicy.handoff(cancel,auth,safe,locked,away);
            if (cancel || !auth || !safe || locked && !away) assertFalse(actual);
            else assertTrue(actual);
        }
    }
    @Test public void nativeGoingAwayAlreadyCountsAsHandoffBeforeShownFalseCallback() {
        assertTrue(SceneExitPolicy.handoff(false,true,true,true,true));
        assertTrue(SceneExitPolicy.handoff(false,true,true,false,false));
        // Occlusion is not unlock: the native locked bit remains true.
        assertFalse(SceneExitPolicy.handoff(false,true,true,true,false));
        assertFalse(SceneActivation.pauseOnRelease(true,true,false,true));
    }
    @Test public void homeAnimationIsNotConsumedDuringWakeWarmup() {
        // Trace: early HOME RESUME at :35 consumed animation; real USER_PRESENT was :38.
        for (int warmFrame=0;warmFrame<240;warmFrame++)
            assertFalse(SceneExitPolicy.activateRenderer(true,false));
        assertTrue(SceneExitPolicy.activateRenderer(true,true));
        // Applications must retain their live prewarm rendering.
        assertTrue(SceneExitPolicy.activateRenderer(false,false));
    }
    @Test public void lateDesktopWithholdsOnlyHomeEarlyResume() {
        // Trace boot152: HOME_LAUNCHER_ONRESUME + UNLOCK_ANIMATION at 41.414 (scene resume),
        // keyguard unlocked at 42.671; the held locked-state callback did not move the animation.
        assertFalse(SceneExitPolicy.requestEarlyResume(true,"HOME"));
        assertFalse(SceneExitPolicy.requestEarlyResume(true,"NONE"));
        assertFalse(SceneExitPolicy.requestEarlyResume(true,null));
        assertTrue(SceneExitPolicy.requestEarlyResume(true,"APP"));
        for (String target : new String[]{"HOME","APP","NONE",null})
            assertTrue(SceneExitPolicy.requestEarlyResume(false,target));
    }
}
