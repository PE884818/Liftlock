package com.zhuanz.flymemotion.core;

/** Repeat showing(true) is not a new lock cycle. AOD/removal follows native KC semantics. */
public final class LockCycle {
    private LockCycle() {}
    public static boolean newLock(boolean previousShowing, boolean previousGoingAway, boolean previousAod,
                                  boolean showing, boolean aod) {
        boolean aodRemoved = previousAod && !aod;
        return aod && !previousAod || showing && (!previousShowing || previousGoingAway && !aodRemoved);
    }
}
