package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.core.BackdropBlend;

/** Full protection below Shade: live HOME fallback, independently of the return app's first frame. */
final class ServerSceneCover {
    private final Handler handler;
    private final BackdropBlend blend = new BackdropBlend();
    private SurfaceControl cover, home, parent, pendingHome, nativeHome;
    private long homeSource = -1, pendingSource = -1, homeGeneration = -1, binding, fadeToken = -1;
    private int layer, width, height;
    private boolean homeCommitted, protecting = true;
    private boolean fallbackDisabled;
    private boolean nativeHomeCommitted;
    private Runnable changed = () -> {};
    private java.util.function.Consumer<RuntimeException> failure = e -> android.util.Log.w("FlymeMotion", "Backdrop transition unavailable", e);
    ServerSceneCover(Handler handler) { this.handler = handler; }
    void onChanged(Runnable callback) { changed = callback; }
    void onFailure(java.util.function.Consumer<RuntimeException> callback) { failure = callback; }
    private static long id(SurfaceControl s) {
        return s == null || !s.isValid() ? -1 : ((Number) NativeReflection.call(s, "getLayerId")).longValue();
    }
    private static SurfaceControl mirror(SurfaceControl source) {
        SurfaceControl result = (SurfaceControl) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class,
                "mirrorSurface", SurfaceControl.class), null, source);
        if (result == null || !result.isValid()) throw new IllegalStateException("Protection mirror unavailable");
        return result;
    }
    private void geometry(SurfaceControl.Transaction t, SurfaceControl s) {
        t.reparent(s, parent).setLayer(s, layer).setPosition(s, 0, 0).setScale(s, 1, 1);
        NativeReflection.invoke(NativeReflection.method(SurfaceControl.Transaction.class, "setCrop", SurfaceControl.class, Rect.class),
                t, s, new Rect(0, 0, width, height));
    }
    synchronized void bind(SurfaceControl source, SurfaceControl nextParent, int nextLayer, int w, int h, Runnable committed) {
        if (id(source) < 0 || id(nextParent) < 0 || w <= 0 || h <= 0)
            throw new IllegalStateException("Protection cover binding unavailable");
        SurfaceControl next = mirror(source), old = cover;
        parent = nextParent; layer = nextLayer; width = w; height = h;
        cover = next; protecting = true; blend.protect();
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl fallback = id(pendingHome) > 0 ? pendingHome : homeCommitted && id(home) > 0 ? home : null;
            geometry(t, next); t.setAlpha(next, 1).setVisibility(next, fallback == null && !nativeHomeCommitted);
            if (fallback != null) { geometry(t, fallback); t.setAlpha(fallback, 1).setVisibility(fallback, true); }
            if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> { if (old != null) old.release(); committed.run(); }); t.apply();
        }
    }
    /** Caller has validated this HOME engine's current client identity, EGL swap and visibility. */
    synchronized void prepareHome(SurfaceControl source, long generation) {
        long sourceId = id(source);
        if (fallbackDisabled || !bound() || sourceId < 0 || generation <= 0 || sourceId == pendingSource) return;
        if (sourceId == homeSource && backdropReady()) { homeGeneration = generation; return; }
        // Wait for the previous atomic replacement before allocating another one.
        if (pendingHome != null) return;
        SurfaceControl next = mirror(source), old = home;
        long serial = ++binding;
        pendingHome = next; pendingSource = sourceId;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            geometry(t, next); t.setAlpha(next, protecting ? 1 : blend.alpha(fadeToken, SystemClock.uptimeMillis()))
                    .setVisibility(next, protecting || !blend.exposed());
            if (id(cover) > 0 && protecting) t.setVisibility(cover, false);
            if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> {
                synchronized (ServerSceneCover.this) {
                    if (serial != binding || pendingHome != next) { next.release(); return; }
                    home = next; homeSource = sourceId; homeGeneration = generation; homeCommitted = true;
                    pendingHome = null; pendingSource = -1;
                    if (old != null) old.release();
                }
                changed.run();
            }); t.apply();
        }
    }
    synchronized boolean bound() { return id(cover) > 0; }
    synchronized boolean backdropReady() { return nativeHomeCommitted && id(nativeHome) > 0 || homeCommitted && id(home) > 0; }
    synchronized long backdropGeneration() { return backdropReady() ? homeGeneration : -1; }
    synchronized long backdropLayer() { return nativeHomeCommitted ? id(nativeHome) : backdropReady() ? id(home) : -1; }
    /** HOME has its own wallpaper below its icons; no foreground wallpaper clone is needed. */
    synchronized void useNativeHome(SurfaceControl source, long generation) {
        if (!bound() || id(source) <= 0 || generation <= 0) return;
        if (id(nativeHome) == id(source)) { homeGeneration = generation; return; }
        long serial = ++binding;
        SurfaceControl oldHome = home, oldPending = pendingHome;
        nativeHome = source; nativeHomeCommitted = false; home = pendingHome = null;
        homeCommitted = false; homeSource = pendingSource = -1; homeGeneration = generation;
        blend.protect(); protecting = true;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setVisibility(cover, false);
            for (SurfaceControl old : new SurfaceControl[]{oldHome, oldPending}) if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> {
                synchronized (ServerSceneCover.this) {
                    if (oldHome != null) oldHome.release();
                    if (serial != binding || nativeHome != source) return;
                    nativeHomeCommitted = true;
                }
                changed.run();
            }); t.apply();
        }
    }
    synchronized void requireLockProtection() {
        if (nativeHome == null) return;
        ++binding; nativeHome = null; nativeHomeCommitted = false; homeGeneration = -1;
        protecting = false; protect();
    }
    synchronized boolean exposureComplete() { return blend.exposed(); }
    synchronized void protect(SurfaceControl.Transaction t) {
        blend.protect(); protecting = true;
        if (nativeHomeCommitted && id(nativeHome) > 0) {
            if (bound()) t.setVisibility(cover, false);
            return;
        }
        SurfaceControl fallback = id(pendingHome) > 0 ? pendingHome : homeCommitted && id(home) > 0 ? home : null;
        if (fallback != null) t.setAlpha(fallback, 1).setVisibility(fallback, true);
        if (bound()) t.setAlpha(cover, 1).setVisibility(cover, fallback == null);
    }
    synchronized void reveal(boolean realSceneReady) {
        if (!realSceneReady || !bound() || !protecting || pendingHome != null) return;
        long token = blend.reveal(SystemClock.uptimeMillis(), true); fadeToken = token;
        if (token < 0) return;
        protecting = false;
        if (homeCommitted && id(home) > 0) scheduleFade(token, 0);
        else try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setVisibility(cover, false);
            t.addTransactionCommittedListener(handler::post, () -> complete(token)); t.apply();
        }
    }
    private void scheduleFade(long token, long delay) {
        handler.postDelayed(() -> {
            try { fade(token); }
            catch (RuntimeException error) {
                synchronized (this) {
                    if (!blend.current(token)) return;
                    fallbackDisabled = true; homeCommitted = false; blend.protect(); protecting = true;
                    // A failed optional fade must not crash the system_server Handler.
                    try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                        if (bound()) t.setAlpha(cover, 1).setVisibility(cover, true);
                        if (id(home) > 0) t.setVisibility(home, false);
                        t.apply();
                    } catch (RuntimeException restoreError) { error.addSuppressed(restoreError); }
                }
                failure.accept(error);
            }
        }, delay);
    }
    private void fade(long token) {
        synchronized (this) {
            if (!blend.current(token) || !backdropReady()) return;
            if (pendingHome != null) { scheduleFade(token, 8); return; }
            float alpha = blend.alpha(token, SystemClock.uptimeMillis());
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                t.setAlpha(home, alpha);
                if (bound()) t.setVisibility(cover, false);
                if (alpha == 0) {
                    t.setVisibility(home, false);
                    t.addTransactionCommittedListener(handler::post, () -> complete(token));
                }
                t.apply();
            }
            if (alpha > 0) scheduleFade(token, 8);
        }
    }
    private void complete(long token) {
        synchronized (this) { if (!blend.complete(token)) return; }
        changed.run();
    }
    synchronized void protect() {
        if (!protecting && bound()) try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { protect(t); t.apply(); }
    }
    synchronized void retire(SurfaceControl.Transaction t, Runnable committed) {
        ++binding; blend.protect();
        SurfaceControl old = cover, oldHome = home, oldPending = pendingHome;
        cover = home = pendingHome = parent = nativeHome = null; protecting = true; homeCommitted = nativeHomeCommitted = false;
        homeSource = pendingSource = homeGeneration = -1;
        for (SurfaceControl s : new SurfaceControl[]{old, oldHome, oldPending}) if (id(s) > 0) t.setVisibility(s, false).reparent(s, null);
        t.addTransactionCommittedListener(handler::post, () -> {
            if (old != null) old.release(); if (oldHome != null) oldHome.release();
            // Pending prepare callbacks own their wrapper and reject this retired binding.
            committed.run();
        });
    }
}
