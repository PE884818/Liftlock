package com.zhuanz.flymemotion.core;

/** Match the engine's live root to WMS's registered client surface, not its server parent. */
public final class WallpaperFrameBinding {
    private WallpaperFrameBinding() {}
    public static boolean matches(int roles, int requiredRole, boolean currentClient,
                                  boolean drawn, long drawSequence, long generation,
                                  long engineRoot, long registeredClientRoot) {
        return (roles & requiredRole) != 0 && currentClient && drawn && drawSequence > 0
                && generation > 0 && engineRoot > 0 && registeredClientRoot > 0
                && engineRoot == registeredClientRoot;
    }
}
