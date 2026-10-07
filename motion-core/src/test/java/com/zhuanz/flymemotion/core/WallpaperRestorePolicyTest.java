package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class WallpaperRestorePolicyTest {
    @Test public void successfulExitCannotRestoreLockEvenWhenNativeRequestsIt() {
        for(boolean request:new boolean[]{false,true})for(boolean client:new boolean[]{false,true})
            assertFalse(WallpaperRestorePolicy.sourceVisible(true,request,client));
    }
    @Test public void cancellationRetainsTheNativeFadeUntilItsClientActuallyHides() {
        assertTrue(WallpaperRestorePolicy.sourceVisible(false,false,true));
        assertTrue(WallpaperRestorePolicy.sourceVisible(false,true,false));
        assertFalse(WallpaperRestorePolicy.sourceVisible(false,false,false));
    }
    @Test public void delayedNativeShowsRemainBlockedUntilTheNextLockBoundary() {
        for(int i=0;i<100;i++) {
            assertFalse(WallpaperRestorePolicy.commandVisible(true,true));
            assertFalse(WallpaperRestorePolicy.commandVisible(true,false));
        }
        assertTrue(WallpaperRestorePolicy.commandVisible(false,true));
    }
}
