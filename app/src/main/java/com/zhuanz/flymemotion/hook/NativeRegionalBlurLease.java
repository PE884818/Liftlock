package com.zhuanz.flymemotion.hook;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Keep the native blur producer, but reserve its full-window radius while our cropped sheet owns blur. */
final class NativeRegionalBlurLease {
    private record Request(Object producer, int radius) {}
    private final WeakHashMap<Object, Request> latest = new WeakHashMap<>();
    private final Method wallpaperSetter;
    private final Class<?> rootType;
    private Object root, producer;
    private int fullRadius, wallpaperRadius;
    private boolean writing, clamped;
    private int clampedRequests;
    private String clampLog = "";
    NativeRegionalBlurLease(ClassLoader loader) throws ClassNotFoundException {
        rootType = Class.forName("android.view.ViewRootImpl", false, loader);
        // MIUI's wallpaper self-blur. Without it only the Shade's full-window radius is leased.
        wallpaperSetter = NativeReflection.methodOrNull(rootType, "setWallpaperBlur", int.class);
    }
    boolean wallpaperBlur() { return wallpaperSetter != null; }
    /** MIUI's applyBlur(ViewRootImpl, int), else AOSP's applyBlur(ViewRootImpl, int, boolean opaque). */
    private void applyBlur(Object owner, Object window, int radius) {
        Method two = NativeReflection.methodOrNull(owner.getClass(), "applyBlur", rootType, int.class);
        if (two != null) { NativeReflection.invoke(two, owner, window, radius); return; }
        Method three = NativeReflection.methodOrNull(owner.getClass(), "applyBlur", rootType, int.class, boolean.class);
        if (three != null) NativeReflection.invoke(three, owner, window, radius, false);
    }
    int fullWindowRequest(Object owner, Object window, int radius) {
        if (writing || window == null) return radius;
        latest.put(window, new Request(owner, radius));
        if (window != root || root == null) return radius;
        producer = owner; fullRadius = radius; note("full", radius); return 0;
    }
    int wallpaperRequest(Object window, int radius) {
        if (writing || window != root || root == null) return radius;
        wallpaperRadius = radius; note("wallpaper", radius); return 0;
    }
    private void note(String kind, int radius) {
        if (clamped && radius != 0 && clampedRequests++ < 6) clampLog += kind + "=" + radius + " ";
    }
    void begin(Object window, Object fallbackProducer) {
        if (root == window && !clamped) return;
        release(); root = window;
        Request request = latest.get(window);
        producer = request == null ? fallbackProducer : request.producer();
        fullRadius = request == null ? (producer != null && NativeReflection.optGet(producer, "lastTargetViewRootImpl") == window
                && NativeReflection.optGet(producer, "lastAppliedBlur") instanceof Number n ? n.intValue() : 0) : request.radius();
        wallpaperRadius = NativeReflection.optGet(window, "mDeductedblur") instanceof Number n ? n.intValue() : 0;
        writing = true;
        try {
            if (producer != null) applyBlur(producer, window, 0);
            if (wallpaperSetter != null) NativeReflection.invoke(wallpaperSetter, window, 0);
        } finally { writing = false; }
    }
    /**
     * After a completed unlock, MIUI's own keyguard fade still asks for Shade/wallpaper blur.
     * setWallpaperBlur self-blurs every wallpaper leash, the desktop's too, which is already
     * exposed here. Keep both radii at 0 while that fade runs; the caller ends it.
     */
    void clampAfterUnlock() {
        if (root == null) return;
        clamped = true; clampedRequests = 0; clampLog = "";
        // Lock-time radii are not the unlocked state; only requests made from here count.
        fullRadius = 0; wallpaperRadius = 0;
    }
    boolean clamped() { return clamped; }
    String clampLog() { return clampLog.isEmpty() ? "none" : clampLog.trim(); }
    void release() { release(false); }
    /** {@code cleared}: write 0 instead of the latest recorded native radii. */
    void release(boolean cleared) {
        Object oldRoot = root, oldProducer = producer;
        int radius = cleared ? 0 : fullRadius, wallpaper = cleared ? 0 : wallpaperRadius;
        root = producer = null; clamped = false;
        if (oldRoot == null) return;
        writing = true;
        try {
            if (oldProducer != null) applyBlur(oldProducer, oldRoot, radius);
            if (wallpaperSetter != null) NativeReflection.invoke(wallpaperSetter, oldRoot, wallpaper);
        } finally { writing = false; }
    }
}
