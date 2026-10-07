package com.zhuanz.flymemotion.hook;

import android.os.Handler;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.core.MirrorBinding;
import com.zhuanz.flymemotion.core.WallpaperRestorePolicy;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Constructor;

/** Flyme-style WallpaperWindowToken mirror plus a reversible source-attachment lease. */
final class ServerWallpaperMirror {
    private final MotionModule module;
    private final Handler handler;
    private final Constructor<SurfaceControl> sourceCopy;
    private final MirrorBinding binding = new MirrorBinding();
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    private Object token;
    private SurfaceControl source, mirror, nativeParent;
    private long sequence, session = -1;
    private boolean detached, detaching, ownsSource;
    private boolean exclusiveLock = true;
    private String lastRestore = "none";
    // A retired LOCK source stays hidden throughout native exit, including delayed SHOW
    // transactions built before our restore. Native new-lock/sleep boundaries release it.
    private final java.util.concurrent.ConcurrentHashMap<Integer, SurfaceControl> exitSources = new java.util.concurrent.ConcurrentHashMap<>();
    // The LOCK token's window surfaces, hidden with it. Shell's keyguard handler can reparent and
    // show the token itself inside its transition (one visible frame of the blurred LOCK buffer);
    // it never touches these children. WM still believes they are shown, so they are explicitly
    // shown again at the next lock boundary.
    private final java.util.concurrent.ConcurrentHashMap<Integer, SurfaceControl> exitChildren = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Retiring {
        final Object token;
        final SurfaceControl source, mirror;
        final boolean detached;
        final boolean ownsSource, exclusiveLock;
        SurfaceControl parent;
        Retiring(Object token, SurfaceControl source, SurfaceControl mirror, SurfaceControl parent, boolean detached, boolean ownsSource, boolean exclusiveLock) {
            this.token = token; this.source = source; this.mirror = mirror; this.parent = parent; this.detached = detached;
            this.ownsSource = ownsSource; this.exclusiveLock = exclusiveLock;
        }
    }
    private final List<Retiring> awaitingCover = new ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, java.lang.ref.WeakReference<Object>> exitTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.Predicate<Object> exclusiveRole = token -> false;
    void exclusiveRole(java.util.function.Predicate<Object> role) { exclusiveRole = role; }

    ServerWallpaperMirror(MotionModule module, Handler handler) {
        this.module = module; this.handler = handler;
        try { sourceCopy = SurfaceControl.class.getDeclaredConstructor(SurfaceControl.class, String.class); sourceCopy.setAccessible(true); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Stable wallpaper source handle unavailable", unavailable); }
    }
    void install() {
        var copy = NativeReflection.method(SurfaceControl.class, "copyFrom", SurfaceControl.class, String.class);
        module.hook(copy).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            SurfaceControl receiver = (SurfaceControl)chain.getThisObject(), next = (SurfaceControl)chain.getArg(0);
            int oldId = receiver.isValid() ? (int)layer(receiver) : -1;
            var reference = exitTokens.get(oldId); Object retiredToken = reference == null ? null : reference.get();
            if (!Boolean.TRUE.equals(writing.get()) && exitSources.containsKey(oldId) && retiredToken != null
                    && next != null && next.isValid() && layer(next) != oldId && exclusiveRole.test(retiredToken)) {
                // copyFrom adopts another native layer; retire that generation before a late
                // native SHOW can be submitted. Shared/HOME role is expressly excluded.
                holdExitSource(next, retiredToken);
                writing.set(true);
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { t.setVisibility(next,false).apply(); }
                finally { writing.remove(); }
            }
            return chain.proceed();
        });
        module.deoptimize(copy);
        module.hook(NativeReflection.method(SurfaceControl.Transaction.class, "reparent", SurfaceControl.class, SurfaceControl.class))
            .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                SurfaceControl child = (SurfaceControl) chain.getArg(0);
                if (!Boolean.TRUE.equals(writing.get()) && detached && same(child, source)) {
                    nativeParent = (SurfaceControl) chain.getArg(1);
                    // Observe native reparent intent while retaining the detached live producer.
                    return chain.proceed(new Object[]{child, null});
                }
                if (!Boolean.TRUE.equals(writing.get())) for (Retiring old : awaitingCover)
                    if (old.detached && same(child, old.source)) {
                        old.parent = (SurfaceControl) chain.getArg(1);
                        return chain.proceed(new Object[]{child, null});
                    }
                return chain.proceed();
            });
    }
    void ensure(Object nextToken, long nextSession) { ensure(nextToken, nextSession, true); }
    void ensure(Object nextToken, long nextSession, boolean exclusive) {
        SurfaceControl next = nextToken == null ? null : (SurfaceControl) NativeReflection.call(nextToken, "getSurfaceControl");
        if (next != null && next.isValid() && session == nextSession && exclusive == exclusiveLock && same(next, source) && mirror != null && mirror.isValid()) return;
        // A transient absent target is not permission to remove the currently displayed cover.
        if (next == null || !next.isValid()) return;
        if (awaitingCover.size() >= 8) throw new IllegalStateException("Wallpaper replacement did not commit; rolling back scene");
        SurfaceControl nextParent = (SurfaceControl) NativeReflection.call(nextToken, "getParentSurfaceControl");
        if (!exclusive && same(next,source)) {
            // A shared HOME/LOCK role on the same native root must never inherit old LOCK
            // retirement. Reattach while the independent guard still covers the return scene.
            SurfaceControl held = exitSources.remove((int)layer(next)); if (held != null) held.release(); exitTokens.remove((int)layer(next));
            if (detached) {
                writing.set(true);
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.reparent(source,nextParent).setVisibility(source,true).apply();
                } finally { writing.remove(); }
                detached = detaching = false;
            }
            for (int i=0;i<awaitingCover.size();i++) {
                Retiring old = awaitingCover.get(i);
                if (old.detached && same(old.source,next)) awaitingCover.set(i,new Retiring(old.token,old.source,old.mirror,old.parent,false,old.ownsSource,false));
            }
        }

        SurfaceControl ownedSource;
        try { ownedSource = sourceCopy.newInstance(next, "FlymeMotion-SourceLease"); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Wallpaper source copy failed", unavailable); }
        SurfaceControl nextMirror;
        try {
            nextMirror = (SurfaceControl) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class, "mirrorSurface", SurfaceControl.class), null, ownedSource);
            if (nextMirror == null || !nextMirror.isValid()) throw new IllegalStateException("Native wallpaper-token mirror unavailable");
        } catch (Throwable failure) { ownedSource.release(); throw failure; }
        // Never expose an unbound mirror root on a physical display.
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { t.setVisibility(nextMirror, false).apply(); }
        catch (Throwable failure) { nextMirror.release(); ownedSource.release(); throw failure; }
        if (mirror != null || detached) awaitingCover.add(new Retiring(token, source, mirror, nativeParent, detached, ownsSource, exclusiveLock));
        exclusiveLock = exclusive; token = nextToken; source = ownedSource; ownsSource = true; mirror = nextMirror; nativeParent = nextParent; session = nextSession;
        detached = detaching = false;
        binding.begin(session, ++sequence, layer(mirror));
    }
    boolean acknowledge(long id, long generation, long layer) {
        if (!binding.acknowledge(id, generation, layer)) return false;
        // SystemUI has committed the new full cover. Only now may old sources/mirrors retire.
        if (!awaitingCover.isEmpty()) try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            restoreAll(t, drainAwaiting(), true, () -> {}); t.apply();
        }
        return true;
    }
    int awaitingCoverCount() { return awaitingCover.size(); }
    String lastRestore() { return lastRestore; }
    boolean visibility(int id, boolean wanted) {
        // A LOCK producer retired for the native exit stays hidden, detached or not.
        if (exitSources.containsKey(id) || exitChildren.containsKey(id)) return WallpaperRestorePolicy.commandVisible(true, wanted);
        // Mirror clones inherit producer root visibility. The detached live root must remain
        // drawable until both guard and gesture mirrors have been retired atomically.
        if (detached && source != null && source.isValid() && layer(source) == id) return true;
        for (Retiring old : awaitingCover)
            if (old.detached && old.source != null && old.source.isValid() && layer(old.source) == id) return true;
        return WallpaperRestorePolicy.commandVisible(exitSources.containsKey(id), wanted);
    }
    SurfaceControl exitSurface(int id) {
        if (detached && source != null && source.isValid() && layer(source) == id) return source;
        for (Retiring old : awaitingCover)
            if (old.detached && old.source != null && old.source.isValid() && layer(old.source) == id) return old.source;
        return exitSources.get(id);
    }
    // Parcelled/BLAST merged transactions have no Java show() observation. Enforce the
    // retired-source invariant on every server submission, including an empty observed batch.
    void enforceExitVisibility(SurfaceControl.Transaction transaction) {
        for (SurfaceControl held : exitSources.values())
            if (held.isValid()) transaction.setVisibility(held,false);
        for (SurfaceControl held : exitChildren.values())
            if (held.isValid()) transaction.setVisibility(held,false);
    }
    /** Every surface held hidden for the native exit, for a transition's own start transaction. */
    java.util.List<SurfaceControl> exitSurfaces() {
        var all = new ArrayList<SurfaceControl>(exitSources.values()); all.addAll(exitChildren.values()); return all;
    }
    private void hideChildren(Object lockToken, SurfaceControl.Transaction t) {
        for (Object window : (List<?>) NativeReflection.get(lockToken, "mChildren")) {
            SurfaceControl child = (SurfaceControl) NativeReflection.call(window, "getSurfaceControl");
            if (child == null || !child.isValid() || exitChildren.containsKey((int) layer(child))) continue;
            try { exitChildren.put((int) layer(child), sourceCopy.newInstance(child, "FlymeMotion-ExitChild")); }
            catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Exit child copy failed", unavailable); }
            t.setVisibility(child, false);
        }
    }
    /**
     * Outside a scene, WM's own model is the truth for a wallpaper token: put its surface back
     * under the parent WM believes it has, with WM's visibility. WM reparents a container only
     * when its hierarchy changes, so a surface our detach/exit left elsewhere (seen: the LOCK
     * token orphaned at the SurfaceFlinger root while unlocked) would otherwise stay there through
     * a lock that runs no transition over it: black lock wallpaper, and nothing beneath the
     * notification and shortcut blur. Returns null when nothing was touched, else what was done.
     */
    String reconcile(Object token) {
        if (token == null || detached || detaching) return null;
        SurfaceControl surface = (SurfaceControl) NativeReflection.call(token, "getSurfaceControl");
        SurfaceControl parent = (SurfaceControl) NativeReflection.call(token, "getParentSurfaceControl");
        if (surface == null || !surface.isValid() || parent == null || !parent.isValid()) return null;
        // A source detached for a live drag, or one a native animation/transition currently owns.
        // A bound but still attached source is in WM's tree and is reconciled like any other.
        for (Retiring old : awaitingCover) if (old.detached && same(surface, old.source)) return null;
        if (NativeReflection.optCallBool(token, "inTransition", false) || NativeReflection.optCall(token, "getAnimationLeash") != null) return "busy";
        int id = (int) layer(surface);
        boolean visible = !exitSources.containsKey(id) && WallpaperRestorePolicy.sourceVisible(false,
                NativeReflection.optCallBool(token, "isVisibleRequested", false), NativeReflection.optCallBool(token, "isVisible", false));
        writing.set(true);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.reparent(surface, parent);
            // Only ever re-show what WM wants shown; hiding stays WM's own decision.
            if (visible) t.setVisibility(surface, true);
            t.apply();
        } finally { writing.remove(); }
        return "layer=" + id + (visible ? " shown" : "");
    }
    int retiredSourceCount() { return exitSources.size(); }
    void clearExitSources() {
        for (var source : exitSources.values()) source.release();
        exitSources.clear(); exitTokens.clear();
        if (exitChildren.isEmpty()) return;
        writing.set(true);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            for (var child : exitChildren.values()) if (child.isValid()) t.setVisibility(child, true);
            t.apply();
        } finally {
            writing.remove();
            for (var child : exitChildren.values()) child.release();
            exitChildren.clear();
        }
    }
    private void holdExitSource(SurfaceControl source, Object retiredToken) {
        try {
            SurfaceControl held = sourceCopy.newInstance(source, "FlymeMotion-ExitSource");
            int id = (int) layer(source);
            if (retiredToken != null) exitTokens.put(id,new java.lang.ref.WeakReference<>(retiredToken));
            SurfaceControl previous = exitSources.put(id, held); if (previous != null) previous.release();
        } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Exit source copy failed", unavailable); }
    }
    /**
     * Native going-away for an authenticated HOME exit. WM's keyguard transition reparents both
     * wallpaper tokens into its Transition Root, LOCK above HOME, and shows them for a frame
     * before Shell hides LOCK again: the desktop then flashes the lock producer's last (blurred)
     * buffer. Retire LOCK here, before that transition is built, instead of at scene restore.
     * The sheet is already closed, so no mirror of it is on screen any more.
     */
    /** Same retirement for a LOCK token whose scene already ended (a fast swipe ends before going-away). */
    boolean retireTokenForNativeExit(Object lockToken) {
        if (lockToken == null || !exclusiveRole.test(lockToken)) return false;
        SurfaceControl surface = (SurfaceControl) NativeReflection.call(lockToken, "getSurfaceControl");
        if (surface == null || !surface.isValid()) return false;
        writing.set(true);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            if (!exitSources.containsKey((int) layer(surface))) { holdExitSource(surface, lockToken); t.setVisibility(surface, false); }
            hideChildren(lockToken, t);
            t.apply();
        }
        finally { writing.remove(); }
        return true;
    }
    boolean retireForNativeExit() {
        if (source == null || !source.isValid() || token == null || !exclusiveLock || !exclusiveRole.test(token)) return false;
        writing.set(true);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            if (!exitSources.containsKey((int) layer(source))) { holdExitSource(source, token); t.setVisibility(source, false); }
            hideChildren(token, t);
            t.apply();
        }
        finally { writing.remove(); }
        return true;
    }
    void detachWhenReady(boolean authenticated, boolean producerDrawn, Runnable committed) {
        if (detached || detaching || binding.ready() || !binding.canDetach(authenticated, producerDrawn) || source == null || !source.isValid()) return;
        if (!exclusiveLock) { binding.detached(); committed.run(); return; }
        detaching = true; detached = true; long id = session, generation = binding.generation();
        writing.set(true);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.reparent(source, null);
            t.addTransactionCommittedListener(handler::post, () -> {
                if (id == session && generation == binding.generation() && detached) {
                    detaching = false; binding.detached(); committed.run();
                }
            });
            t.apply();
        } finally { writing.remove(); }
    }
    SurfaceControl source() { return source; }
    boolean ready() { return binding.ready(); }
    boolean committed() { return binding.committed(); }
    long generation() { return binding.generation(); }
    SurfaceControl surface() { return mirror; }
    void restore() { restore(() -> {}); }
    void restore(Runnable committed) {
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            restore(t, committed); t.apply();
        }
    }
    /** The caller can restore leased scene visibility and original wallpaper atomically. */
    void restore(SurfaceControl.Transaction t, Runnable committed) {
        restore(t, false, committed);
    }
    void restore(SurfaceControl.Transaction t, boolean handoff, Runnable committed) {
        List<Retiring> retired = drainAwaiting();
        if (mirror != null || detached) retired.add(new Retiring(token, source, mirror, nativeParent, detached, ownsSource, exclusiveLock));
        mirror = source = nativeParent = null; token = null; session = -1;
        detached = detaching = ownsSource = false; binding.clear();
        restoreAll(t, retired, handoff, committed);
    }
    private List<Retiring> drainAwaiting() {
        List<Retiring> retired = new ArrayList<>(awaitingCover); awaitingCover.clear(); return retired;
    }
    private void restoreAll(SurfaceControl.Transaction t, List<Retiring> retired, Runnable committed) {
        restoreAll(t, retired, false, committed);
    }
    private void restoreAll(SurfaceControl.Transaction t, List<Retiring> retired, boolean handoff, Runnable committed) {
        StringBuilder restored = new StringBuilder();
        lastRestore = "none";
        if (retired.isEmpty()) {
            t.addTransactionCommittedListener(handler::post, committed::run); return;
        }
        writing.set(true);
        try {
            for (Retiring old : retired) {
                boolean retireLock = handoff && old.exclusiveLock && exclusiveRole.test(old.token);
                if (old.source != null && old.source.isValid() && retireLock) {
                    holdExitSource(old.source, old.token);
                    t.setVisibility(old.source, false);
                }
                if (old.detached && old.source != null && old.source.isValid()) {
                    SurfaceControl restoreParent = old.parent;
                    if (restoreParent != null && !restoreParent.isValid())
                        restoreParent = (SurfaceControl) NativeReflection.call(old.token, "getParentSurfaceControl");
                    t.reparent(old.source, restoreParent);
                    // Successful exit keeps the retired LOCK producer hidden until the next
                    // native lock boundary. Cancellation preserves requested OR deferred
                    // client visibility so an in-flight native fade does not lose wallpaper.
                    if (old.token != null) {
                        boolean requested = (boolean) NativeReflection.call(old.token, "isVisibleRequested");
                        boolean client = (boolean) NativeReflection.call(old.token, "isVisible");
                        t.setVisibility(old.source, WallpaperRestorePolicy.sourceVisible(retireLock, requested, client));
                        if (restored.length() < 400) restored.append("layer=").append(layer(old.source))
                                .append(" requested=").append(requested).append(" client=").append(client)
                                .append(" exitHeld=").append(retireLock).append(';');
                    } else if (retireLock) t.setVisibility(old.source, false);
                }
                if (old.mirror != null && old.mirror.isValid()) t.setVisibility(old.mirror, false).reparent(old.mirror, null);
            }
            lastRestore = restored.length() == 0 ? "none" : restored.toString();
            t.addTransactionCommittedListener(handler::post, () -> {
                for (Retiring old : retired) {
                    if (old.mirror != null) old.mirror.release();
                    if (old.ownsSource && old.source != null) old.source.release();
                }
                committed.run();
            });
        } finally { writing.remove(); }
    }
    private static boolean same(SurfaceControl a, SurfaceControl b) {
        return a == b || a != null && b != null && a.isValid() && b.isValid()
                && (boolean) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class, "isSameSurface", SurfaceControl.class), a, b);
    }
    private static long layer(SurfaceControl surface) { return ((Number) NativeReflection.call(surface, "getLayerId")).longValue(); }
}

