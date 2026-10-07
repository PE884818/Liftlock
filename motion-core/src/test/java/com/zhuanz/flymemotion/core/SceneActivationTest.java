package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneActivationTest {
    @Test public void everyUnqualifiedSceneKeepsNativeLifecycle() {
        for(int bits=0;bits<32;bits++)assertEquals(bits==31,SceneActivation.permitted(
                (bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0));
    }
    @Test public void nativeUnlockAndPreviouslyResumedAppAreNeverPausedByRollback() {
        assertTrue(SceneActivation.pauseOnRelease(true,true,true,false));
        assertFalse(SceneActivation.pauseOnRelease(true,true,true,true));
        assertFalse(SceneActivation.pauseOnRelease(true,true,false,false));
        assertFalse(SceneActivation.pauseOnRelease(false,true,true,false));
        assertFalse(SceneActivation.pauseOnRelease(true,false,true,false));
    }
}
