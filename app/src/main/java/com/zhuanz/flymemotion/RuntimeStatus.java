package com.zhuanz.flymemotion;

import android.os.Bundle;

/** A process receipt is evidence of registration/callbacks, never evidence of accepted feel. */
public final class RuntimeStatus {
    public enum State { MISSING, INITIALIZING, LOADED, UNSUPPORTED, FAILED }
    public final State state;
    public final long hooks, motion, blur, entry, configRevision, touch, drag, height, release;
    public final long intercept, fling, spring, fastRedirect, wallpaperFrames;
    public final long coordinatedRelease, nativeRelease;
    public final String releaseReason, gestureJournal;
    public final boolean fresh, enabled;
    public final String error, moduleVersion, scene, gesture;
    public final String blocked, layers, wallpaper, liveScene;
    public final boolean panelFound, keyguardFound, wallpaperBound;
    public final String sceneReadiness, returnTarget, lastHomeReadiness;
    public final long sceneSession, surfaceGeneration, sceneWorkMaxMs;
    public final long scaleSuppressed;
    public final String motionFrame;
    public final String desktopAnimationState,desktopAnimationDetail;
    /** CompatLog lines: which adapter parts are live on this build. */
    public final String compat;
    private final String packageName;

    private RuntimeStatus(Bundle data, String prefix, long now, int boot) {
        packageName = prefix.substring(0, prefix.length() - 1);
        String value = data.getString(prefix + "state", "");
        state = switch (value) {
            case "initializing" -> State.INITIALIZING;
            case "loaded" -> State.LOADED;
            case "unsupported" -> State.UNSUPPORTED;
            case "failed" -> State.FAILED;
            default -> State.MISSING;
        };
        long age = now - number(data.getString(prefix + "elapsed"));
        fresh = state != State.MISSING && Integer.toString(boot).equals(data.getString(prefix + "boot"))
                && age >= 0 && age < 150_000;
        hooks = number(data.getString(prefix + "hooks")); motion = number(data.getString(prefix + "motion"));
        blur = number(data.getString(prefix + "blur")); entry = number(data.getString(prefix + "entry"));
        configRevision = number(data.getString(prefix + "configRevision"));
        enabled = Boolean.parseBoolean(data.getString(prefix + "enabled", "false"));
        error = data.getString(prefix + "error", "");
        moduleVersion = data.getString(prefix + "moduleVersion", "");
        scene = data.getString(prefix + "scene", ""); gesture = data.getString(prefix + "gesture", "");
        touch = number(data.getString(prefix + "touch")); drag = number(data.getString(prefix + "drag"));
        height = number(data.getString(prefix + "height")); release = number(data.getString(prefix + "release"));
        intercept = number(data.getString(prefix + "intercept")); fling = number(data.getString(prefix + "fling"));
        spring = number(data.getString(prefix + "spring"));
        fastRedirect = number(data.getString(prefix + "fastRedirect")); wallpaperFrames = number(data.getString(prefix + "wallpaperFrames"));
        coordinatedRelease = number(data.getString(prefix + "coordinatedRelease")); nativeRelease = number(data.getString(prefix + "nativeRelease"));
        releaseReason = data.getString(prefix + "releaseReason", "等待释放"); gestureJournal = data.getString(prefix + "gestureJournal", "");
        blocked = data.getString(prefix + "blocked", ""); layers = data.getString(prefix + "layers", "");
        wallpaper = data.getString(prefix + "wallpaper", "等待实时壁纸镜像及返回窗口回执");
        liveScene = data.getString(prefix + "liveScene", "等待系统框架场景回执");
        sceneReadiness = data.getString(prefix + "sceneReadiness", "等待真实场景绘制");
        lastHomeReadiness = data.getString(prefix + "lastHomeReadiness", "尚无桌面场景回执");
        desktopAnimationState=data.getString(prefix+"desktopAnimationState","uninitialized");desktopAnimationDetail=data.getString(prefix+"desktopAnimationDetail","");compat=data.getString(prefix+"compat","");
        returnTarget = data.getString(prefix + "returnTarget", "NONE");
        sceneSession = number(data.getString(prefix + "sceneSession", "-1"));
        surfaceGeneration = number(data.getString(prefix + "surfaceGeneration"));
        sceneWorkMaxMs = number(data.getString(prefix + "sceneWorkMaxMs"));
        scaleSuppressed = number(data.getString(prefix + "scaleSuppressed"));
        motionFrame = data.getString(prefix + "motionFrame", "尚未应用运动帧");
        panelFound = Boolean.parseBoolean(data.getString(prefix + "panelFound", "false"));
        keyguardFound = Boolean.parseBoolean(data.getString(prefix + "keyguardFound", "false"));
        wallpaperBound = Boolean.parseBoolean(data.getString(prefix + "wallpaperBound", "false"));
    }
    public static RuntimeStatus read(Bundle data, String pkg, long now, int boot) {
        return new RuntimeStatus(data == null ? Bundle.EMPTY : data, pkg + ".", now, boot);
    }
    public boolean currentModule() { return fresh && TargetBuild.compatibleRuntime(packageName, moduleVersion); }
    private static long number(String s) { try { return Long.parseLong(s); } catch (RuntimeException ignored) { return 0; } }
}
