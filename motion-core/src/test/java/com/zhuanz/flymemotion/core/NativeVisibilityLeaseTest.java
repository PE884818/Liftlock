package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeVisibilityLeaseTest {
    @Test public void cancelledGestureRestoresHiddenClientAfterTemporaryDrawing() {
        var lease = new NativeVisibilityLease(); lease.begin(false);
        assertTrue(lease.nativeChange(false, true));
        assertFalse(lease.end()); assertFalse(lease.active());
    }
    @Test public void successfulUnlockRetainsLatestNativeVisibleState() {
        var lease = new NativeVisibilityLease(); lease.begin(false);
        assertTrue(lease.nativeChange(false, true));
        assertTrue(lease.nativeChange(true, true));
        assertTrue(lease.end());
    }
    @Test public void authenticationRevocationStopsOverridingHiddenIntent() {
        var lease = new NativeVisibilityLease(); lease.begin(false);
        assertTrue(lease.nativeChange(false, true));
        assertFalse(lease.nativeChange(false, false)); assertFalse(lease.end());
    }
    @Test public void latestHideOverridesOriginalVisibleBaseline() {
        var lease = new NativeVisibilityLease(); lease.begin(true);
        assertTrue(lease.nativeChange(false, true)); assertFalse(lease.end());
    }
    @Test public void aLeaseCannotOverwriteAnUnrestoredClient() {
        var lease = new NativeVisibilityLease(); lease.begin(false);
        assertThrows(IllegalStateException.class, () -> lease.begin(true));
        lease.end(); lease.begin(true); assertTrue(lease.end());
    }
    @Test public void inactiveLeasePreservesNativeChanges() {
        var lease = new NativeVisibilityLease();
        assertFalse(lease.nativeChange(false, true)); assertTrue(lease.nativeChange(true, false));
    }
    @Test public void unlockHandoffDoesNotApplyStaleHideOrPinFutureNativeChanges() {
        var lease=new NativeVisibilityLease(); lease.begin(false);
        assertTrue(lease.nativeChange(false,true));
        lease.handoff(); assertFalse(lease.active());
        assertTrue(lease.nativeChange(true,false));
        assertFalse(lease.nativeChange(false,true));
        lease.begin(false);assertFalse(lease.end());
    }
}
