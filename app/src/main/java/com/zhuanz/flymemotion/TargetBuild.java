package com.zhuanz.flymemotion;

import android.os.Build;

public final class TargetBuild {
    public static final String MODULE_VERSION = "0.3.5-stage16";
    /**
     * Lock scene protocol unchanged; notification integration has been removed. stage2-stage4
     * changed SystemUI only; stage5 retires the LOCK wallpaper at native going-away in the
     * framework. An older framework still runs the same scene protocol, it just keeps the flash.
     * stage6 changes the framework only (a fast swipe that ended before going-away), so every
     * stage5 process except the framework is still current; the framework needs a reboot.
     * stage7 (framework only) hides LOCK inside the keyguard transition's start transaction;
     * stage8 (framework only) adds the exit journal; stage9 (framework only) finds the LOCK
     * token and the HOME destination itself when a fast swipe was rolled back before binding;
     * stage10 (framework only) also hides the LOCK token's window surfaces, which Shell never shows;
     * stage11 (SystemUI only) clears the stuck fling-required flag once the stack becomes SHADE;
     * stage12 removes every device/system gate and rounds the lifted sheet with the panel corners
     * (SystemUI; the framework is unchanged since stage10); stage13 resolves the MiWallpaper
     * engine by type and counts frames at EGL14 on builds other than the inspected one;
     * stage14 (SystemUI only) rounds only the sheet's bottom corners, with a user radius;
     * stage15 (every process) resolves members with fallbacks for other HyperOS 3/4 builds,
     * splits each adapter into core and optional parts and reports them as compat;
     * stage16 (framework only) puts wallpaper token surfaces back under WM's parent after sleep,
     * wake and a new lock, so the LOCK wallpaper is never left orphaned off-screen.
     */
    public static boolean compatibleRuntime(String pkg, String version) {
        // stage16 changed only the framework; the other processes still run stage15 code.
        if ("android".equals(pkg)) return MODULE_VERSION.equals(version);
        return MODULE_VERSION.equals(version) || "0.3.5-stage15".equals(version);
    }
    public static boolean compatibleProducerProtocol(String version) {
        return MODULE_VERSION.equals(version) || "0.3.5-stage15".equals(version) || "0.3.5-stage10".equals(version) || "0.3.5-stage11".equals(version)||"0.3.5-stage12".equals(version)||"0.3.5-stage13".equals(version)||"0.3.5-stage14".equals(version) || "0.3.5-stage5".equals(version)||"0.3.5-stage6".equals(version)||"0.3.5-stage7".equals(version)||"0.3.5-stage8".equals(version)||"0.3.5-stage9".equals(version) || "0.3.5-stage1".equals(version) || "0.2.23-stage1".equals(version) || "0.2.18-stage1".equals(version);
    }
    /**
     * Gate for the module's own cross-process messages only: any 0.3.5 hook stage speaks the same
     * scene/producer protocol. Not a device or system check; compatibleRuntime stays for display.
     */
    public static boolean sameProtocol(String version) {
        return version != null && version.startsWith("0.3.5-stage");
    }
    public static final String DEVICE = "myron";
    public static boolean nativeScope(String pkg) {
        return "system".equals(pkg)||"android".equals(pkg)||"com.android.systemui".equals(pkg)
            ||"com.android.settings".equals(pkg)||"com.miui.miwallpaper".equals(pkg);
    }
    public static final String INCREMENTAL = "OS4.0.0.30.XPMCNXM";
    public static final int SYSTEMUI_VERSION = 202602260;
    private TargetBuild() {}
    /**
     * Information only: whether this is the build the port was developed on. Nothing is gated on
     * it any more; whether another device or system works is for the tester to find out.
     */
    public static boolean referenceBuild() {
        return Build.VERSION.SDK_INT == 37 && DEVICE.equals(Build.DEVICE)
                && INCREMENTAL.equals(Build.VERSION.INCREMENTAL);
    }
    public static String display() { return DEVICE + " · 澎湃 OS 4.0.0.30 · Android 17"; }
}
