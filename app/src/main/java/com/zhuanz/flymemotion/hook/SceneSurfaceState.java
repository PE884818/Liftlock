package com.zhuanz.flymemotion.hook;

import android.os.Handler;
import android.view.SurfaceControl;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import com.zhuanz.flymemotion.core.SurfaceTopology;

/** Observe server-created surface defaults and committed visibility commands, including merges.
 * Lease promotions preserve the latest native desired state, not an inferred Activity visibility.
 * A commit is visibility evidence; drawing evidence is supplied separately by the producer.
 */
final class SceneSurfaceState {
    private final MotionModule module;
    private final BooleanSupplier allowed;
    private final SurfaceTopology topology = new SurfaceTopology();
    private final Map<Integer, WeakReference<SurfaceControl>> controls = new LinkedHashMap<>();
    private int updatesSinceCollection;
    private final Map<Integer, Boolean> nativeDesired = new LinkedHashMap<>();
    private final Map<Integer, SurfaceControl> promoted = new LinkedHashMap<>();
    private final Map<Object, Map<Integer, Boolean>> pending = new java.util.WeakHashMap<>();
    private final Map<Object, Map<Integer, Integer>> pendingParents = new java.util.WeakHashMap<>();
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    private final Method layerId = NativeReflection.method(SurfaceControl.class, "getLayerId");
    private final Method show = NativeReflection.method(SurfaceControl.Transaction.class, "show", SurfaceControl.class);
    private final Method hide = NativeReflection.method(SurfaceControl.Transaction.class, "hide", SurfaceControl.class);
    private final Method committed;
    private final Class<?> listenerType;
    private Handler handler;
    private java.util.function.Consumer<SurfaceControl.Transaction> hazard = t -> {};
    private Runnable changed = () -> {};
    void onHazard(java.util.function.Consumer<SurfaceControl.Transaction> observer, Runnable committed) { hazard = observer; changed = committed; }
    private void protectHazard(SurfaceControl.Transaction t) {
        writing.set(true); try { hazard.accept(t); } finally { writing.remove(); }
    }
    private java.util.function.BiPredicate<Integer, Boolean> visibilityFilter = (id, wanted) -> wanted;
    private java.util.function.Consumer<SurfaceControl.Transaction> enforceRetired = t -> {};
    synchronized void enforceRetiredSources(java.util.function.Consumer<SurfaceControl.Transaction> enforce) { enforceRetired = enforce; }
    private java.util.function.IntFunction<SurfaceControl> exitSource = id -> null;
    synchronized void filterExitVisibility(java.util.function.BiPredicate<Integer, Boolean> filter,
                                          java.util.function.IntFunction<SurfaceControl> source) {
        visibilityFilter = filter; exitSource = source;
    }
    SceneSurfaceState(MotionModule module, ClassLoader loader, BooleanSupplier allowed) throws ClassNotFoundException {
        this.module = module; this.allowed = allowed;
        listenerType = Class.forName("android.view.SurfaceControl$TransactionCommittedListener", false, loader);
        committed = NativeReflection.method(SurfaceControl.Transaction.class, "addTransactionCommittedListener", Executor.class, listenerType);
    }
    void attach(Handler handler) { this.handler = handler; }
    void install() {
        hook(NativeReflection.method(SurfaceControl.Builder.class, "build"), chain -> {
            SurfaceControl surface = (SurfaceControl) chain.proceed();
            synchronized (this) {
                SurfaceControl parent = (SurfaceControl) NativeReflection.get(chain.getThisObject(), "mParent");
                topology.created(id(surface), parent == null ? 0 : id(parent),
                        (NativeReflection.integer(chain.getThisObject(), "mFlags") & 4) == 0);
                remember(surface); remember(parent); collect();
            }
            return surface;
        });
        hook(NativeReflection.method(SurfaceControl.class, "copyFrom", SurfaceControl.class, String.class), chain -> {
            synchronized (this) {
                if (promoted.containsKey(id((SurfaceControl)chain.getThisObject()))
                        && id((SurfaceControl)chain.getThisObject()) != id((SurfaceControl)chain.getArg(0)))
                    try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { protectHazard(t); t.apply(); }
            }
            Object result = chain.proceed();
            synchronized (this) { remember((SurfaceControl) chain.getThisObject()); }
            return result;
        });
        Method setVisibility = NativeReflection.method(SurfaceControl.Transaction.class, "setVisibility", SurfaceControl.class, boolean.class);
        for (Method method : new Method[]{show, hide, setVisibility}) hook(method, chain -> {
            if (Boolean.TRUE.equals(writing.get())) return chain.proceed();
            SurfaceControl surface = (SurfaceControl) chain.getArg(0);
            boolean wanted = method == setVisibility ? (boolean) chain.getArg(1) : method == show, actual = wanted;
            synchronized (this) {
                int id = id(surface);
                remember(surface);
                if (promoted.containsKey(id)) { nativeDesired.put(id, wanted); actual = allowed.getAsBoolean() || wanted; }
                actual = visibilityFilter.test(id, actual);
                if (!actual && promoted.containsKey(id)) protectHazard((SurfaceControl.Transaction)chain.getThisObject());
                pending.computeIfAbsent(chain.getThisObject(), key -> new LinkedHashMap<>()).put(id, actual);
            }
            if (actual == wanted) return chain.proceed();
            writing.set(true);
            try { return NativeReflection.invoke(actual ? show : hide, chain.getThisObject(), surface); }
            finally { writing.remove(); }
        });
        hook(NativeReflection.method(SurfaceControl.Transaction.class, "merge", SurfaceControl.Transaction.class), chain -> {
            Object result = chain.proceed();
            synchronized (this) {
                Map<Integer, Boolean> from = pending.remove(chain.getArg(0));
                if (from != null) pending.computeIfAbsent(chain.getThisObject(), key -> new LinkedHashMap<>()).putAll(from);
                var parentFrom = pendingParents.remove(chain.getArg(0));
                if (parentFrom != null) pendingParents.computeIfAbsent(chain.getThisObject(), key -> new LinkedHashMap<>()).putAll(parentFrom);
            }
            return result;
        });
        hook(NativeReflection.method(SurfaceControl.Transaction.class, "remove", SurfaceControl.class), chain -> {
            if (!Boolean.TRUE.equals(writing.get())) synchronized (this) {
                if (promoted.containsKey(id((SurfaceControl)chain.getArg(0)))) protectHazard((SurfaceControl.Transaction)chain.getThisObject());
            }
            return chain.proceed();
        });
        hook(NativeReflection.method(SurfaceControl.Transaction.class, "reparent", SurfaceControl.class, SurfaceControl.class), chain -> {
            if (!Boolean.TRUE.equals(writing.get())) synchronized (this) {
                int child = id((SurfaceControl)chain.getArg(0));
                SurfaceControl parent = (SurfaceControl)chain.getArg(1);
                if (promoted.containsKey(child) && !java.util.Objects.equals(topology.parent(child), parent == null ? 0 : id(parent)))
                    protectHazard((SurfaceControl.Transaction)chain.getThisObject());
            }
            Object result = chain.proceed();
            synchronized (this) {
                SurfaceControl child = (SurfaceControl) chain.getArg(0), parent = (SurfaceControl) chain.getArg(1);
                pendingParents.computeIfAbsent(chain.getThisObject(), key -> new LinkedHashMap<>()).put(id(child), parent == null ? 0 : id(parent));
                remember(child); remember(parent);
            }
            return result;
        });
        // Every public wrapper is a hook entry too: compiled wrappers can inline apply(bool,bool)
        // and otherwise bypass its hook, leaving shown ancestors permanently recorded as hidden.
        // Wrappers differ between builds (applyAsyncUnsafe is recent); hook every one present.
        for (Method method : new Method[]{
                NativeReflection.method(SurfaceControl.Transaction.class, "apply"),
                NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "apply", boolean.class),
                NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "applyAsyncUnsafe"),
                NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "apply", boolean.class, boolean.class)}) if (method != null) hook(method, chain -> {
            Map<Integer, Boolean> batch;
            Map<Integer, Integer> parentBatch;
            synchronized (this) {
                batch = pending.remove(chain.getThisObject()); parentBatch = pendingParents.remove(chain.getThisObject());
                // Native may have constructed SHOW while the scene was active and submit it
                // after handoff. Reconcile at submission too, using the stable source handle.
                reconcileExitVisibility((SurfaceControl.Transaction)chain.getThisObject(), batch);
                // A txn can have been built before promotion. Protect at submission as well.
                boolean destructive = batch != null && batch.entrySet().stream().anyMatch(e -> promoted.containsKey(e.getKey()) && !e.getValue());
                destructive |= parentBatch != null && parentBatch.entrySet().stream().anyMatch(e -> promoted.containsKey(e.getKey())
                        && !java.util.Objects.equals(topology.parent(e.getKey()),e.getValue()));
                if (destructive) protectHazard((SurfaceControl.Transaction)chain.getThisObject());
            }
            if (batch != null && !batch.isEmpty() || parentBatch != null && !parentBatch.isEmpty()) {
                // Register before applying; publication occurs only when SF commits the command.
                Executor executor = task -> { Handler h = handler; if (h != null) h.post(task); else task.run(); };
                Object callback = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                        (proxy, callbackMethod, args) -> {
                            if (callbackMethod.getName().equals("onTransactionCommitted")) {
                                synchronized (this) {
                                    if (batch != null) batch.forEach(this::put);
                                    if (parentBatch != null) parentBatch.forEach(topology::reparent);
                                }
                                changed.run();
                            }
                            if (callbackMethod.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (callbackMethod.getName().equals("equals")) return proxy == args[0];
                            if (callbackMethod.getName().equals("toString")) return "FlymeMotion-VisibilityCommit";
                            return null;
                        });
                NativeReflection.invoke(committed, chain.getThisObject(), executor, callback);
            }
            return chain.proceed();
        });
        for (String name : new String[]{"clear", "close"}) hook(NativeReflection.method(SurfaceControl.Transaction.class, name), chain -> {
            synchronized (this) { pending.remove(chain.getThisObject()); pendingParents.remove(chain.getThisObject()); }
            return chain.proceed();
        });
    }
    private final java.util.List<XposedInterface.HookHandle> handles = new java.util.ArrayList<>();
    private void hook(java.lang.reflect.Executable method, XposedInterface.Hooker callback) {
        handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(callback));
        module.deoptimize(method);
    }
    /** Rollback when the rest of the framework scene cannot install on this build. */
    void uninstall() {
        for (var handle : handles) try { handle.unhook(); } catch (Throwable ignored) {}
        handles.clear();
    }
    private int id(SurfaceControl surface) { return ((Number) NativeReflection.invoke(layerId, surface)).intValue(); }
    private synchronized void reconcileExitVisibility(SurfaceControl.Transaction t, Map<Integer,Boolean> batch) {
        writing.set(true);
        try { enforceRetired.accept(t); } finally { writing.remove(); }
        if(batch==null)return;
        for(var command:batch.entrySet()) {
            boolean accepted=visibilityFilter.test(command.getKey(),command.getValue());
            if(accepted==command.getValue())continue;
            SurfaceControl source=exitSource.apply(command.getKey());
            if(source==null||!source.isValid())continue;
            writing.set(true);
            try {NativeReflection.invoke(accepted?show:hide,t,source);}
            finally {writing.remove();}
            command.setValue(accepted);
        }
    }
    private void remember(SurfaceControl surface) {
        if (surface != null && surface.isValid()) controls.put(id(surface), new WeakReference<>(surface));
    }
    synchronized void adopt(SurfaceControl surface) { remember(surface); }
    private void put(int id, boolean shown) { topology.visibility(id, shown); collect(); }
    private void collect() {
        if (topology.size() <= 4096 || ++updatesSinceCollection < 256) return;
        updatesSinceCollection = 0;
        var live = new HashSet<Integer>(promoted.keySet());
        controls.forEach((layer, weak) -> { SurfaceControl control = weak.get(); if (control != null && control.isValid() && id(control) == layer) live.add(layer); });
        var keep = topology.retainLiveChains(live);
        controls.keySet().removeIf(layer -> !keep.contains(layer));
    }
    synchronized boolean shown(SurfaceControl surface) { return surface != null && surface.isValid() && Boolean.TRUE.equals(topology.visibility(id(surface))); }
    synchronized boolean ancestorsShown(SurfaceControl surface) {
        return surface != null && surface.isValid() && topology.blocker(id(surface)).equals("none");
    }
    synchronized boolean nativeAncestorsShown(SurfaceControl surface) {
        if (!ancestorsShown(surface)) return false;
        int current = id(surface); java.util.Set<Integer> seen = new java.util.HashSet<>();
        while (current != 0 && seen.add(current)) {
            if (Boolean.FALSE.equals(nativeDesired.get(current))) return false;
            Integer parent = topology.parent(current); if (parent == null) return false; current = parent;
        }
        return current == 0;
    }
    synchronized String blocker(SurfaceControl surface) {
        return surface == null || !surface.isValid() ? "invalid-window-surface" : topology.blocker(id(surface));
    }
    synchronized boolean promoteChain(SurfaceControl surface, Boolean bufferShown, SurfaceControl.Transaction t) {
        if (!promote(surface, bufferShown, t)) return false;
        int current = id(surface);
        for (int depth = 0; depth < 64; depth++) {
            Integer parent = topology.parent(current); if (parent == null) return false;
            if (parent == 0) return true;
            if (Boolean.TRUE.equals(topology.visibility(parent))) { current = parent; continue; }
            WeakReference<SurfaceControl> handle = controls.get(parent);
            SurfaceControl control = handle == null ? null : handle.get();
            if (control != null && control.isValid() && id(control) != parent) return false;
            if (!promote(control, null, t)) return false;
            current = parent;
        }
        return false;
    }
    synchronized boolean promote(SurfaceControl surface, Boolean bufferShown, SurfaceControl.Transaction t) {
        if (surface == null || !surface.isValid()) return false;
        int id = id(surface); remember(surface); Boolean baseline = topology.visibility(id);
        if (baseline == null) baseline = bufferShown;
        if (baseline == null) return false; // Unknown ancestor state must never be called ready.
        if (!promoted.containsKey(id)) {
            nativeDesired.put(id, baseline); promoted.put(id, surface);
        }
        if (Boolean.TRUE.equals(topology.visibility(id))) return true;
        // Record separately because this is our composition command, not a native desired update.
        pending.computeIfAbsent(t, key -> new LinkedHashMap<>()).put(id, true);
        writing.set(true);
        try { NativeReflection.invoke(show, t, surface); }
        finally { writing.remove(); }
        return true;
    }
    synchronized void restore() {
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            restore(t, false);
            t.apply();
        }
    }
    /** Share the wallpaper restore transaction; handoff never replays pre-unlock hidden state. */
    synchronized void restore(SurfaceControl.Transaction t, boolean handoff) {
        if (handoff) { promoted.clear(); nativeDesired.clear(); return; }
        writing.set(true);
        try {
            for (var entry : promoted.entrySet()) if (entry.getValue().isValid()) {
                boolean wanted = Boolean.TRUE.equals(nativeDesired.get(entry.getKey()));
                NativeReflection.invoke(wanted ? show : hide, t, entry.getValue());
                pending.computeIfAbsent(t, key -> new LinkedHashMap<>()).put(entry.getKey(), wanted);
            }
        } finally { writing.remove(); promoted.clear(); nativeDesired.clear(); }
    }
}
