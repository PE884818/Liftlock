package com.zhuanz.flymemotion.hook;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import com.zhuanz.flymemotion.SceneProtocol;
import com.zhuanz.flymemotion.core.SceneLease;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Adapted lifecycle/frame-request subset of HyperMusicCover v0.7.10 WallpaperProbe (AGPL-3.0).
 * Copyright (C) 2026 zyl6932. Changes: session quorum, EGL swap receipt, no texture/content writes.
 * Source: github.com/zyl6932/HyperMusicCover @ 9462d23d1761e002c1241d874a78b4e4548c8c7f.
 */
final class WallpaperHooks {
    private static final String BASE = "com.miui.miwallpaper.wallpaperservice.impl.ImageEngineImpl";
    private static final String UNIVERSAL = "com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine";
    private static final String STREAM_LOCK = "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardStreamImageEngineImpl";
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final IdentityHashMap<Object, Engine> engines = new IdentityHashMap<>();
    private record Drawing(Engine engine, int bufferId) {}
    private final ThreadLocal<Drawing> drawing = new ThreadLocal<>();
    private final SceneLease lease = new SceneLease();
    private Context context;
    private long generations, statusSequence;
    private Method request;
    // Resolved by TYPE, not by obfuscated name, so another MiWallpaper build still binds.
    private java.lang.reflect.Field serviceOfEngine, engineOfService, eglOfService;
    private boolean knownShape;
    /**
     * No MiWallpaper engine classes on this build (renamed or restructured): every platform
     * WallpaperService.Engine in this process is tracked directly, through the framework's own
     * lifecycle calls, with frames counted at EGL14. The engine then is its own "service".
     */
    private boolean platformMode;
    private String shapeReport = "";
    private String lastPublished = "";
    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            long now=SystemClock.uptimeMillis();
            if (!lease.active(now)) { end();return; }
            if(!lease.active(now)&&lease.id()>=0)end();
            requestFrames(); main.postDelayed(this, 450);
        }
    };
    private static final class Engine {
        final Object service;
        int which;
        Object universal;
        SurfaceControl container, buffer;
        long generation, swaps, baseline;
        boolean visible;
        Engine(Object service, int which) { this.service = service; this.which = which; }
        boolean valid() { return container != null && buffer != null && container.isValid() && buffer.isValid(); }
        boolean drawn() { return valid() && swaps > baseline; }
    }
    WallpaperHooks(MotionModule module, ClassLoader loader, RuntimeReporter reporter) {
        this.module = module; this.loader = loader; this.reporter = reporter;
    }
    void install() throws Throwable {
        Class<?> base = com.zhuanz.flymemotion.core.Members.load(loader, BASE);
        Class<?> universal = com.zhuanz.flymemotion.core.Members.load(loader, UNIVERSAL);
        if (base != null && universal != null) {
            serviceOfEngine = fieldWhere(universal, f -> f.getType() != Object.class && f.getType().isAssignableFrom(base));
            engineOfService = fieldWhere(base, f -> f.getType() == universal);
            eglOfService = fieldWhere(base, f -> declares(f.getType(), android.opengl.EGLSurface.class));
        }
        if (base == null || universal == null || serviceOfEngine == null || engineOfService == null || eglOfService == null) {
            installPlatform((base == null ? "ImageEngineImpl" : universal == null ? "UniversalEngine" : "engine fields") + " not found");
            return;
        }
        Class<?> egl = eglOfService.getType();
        // The inspected APK's request/render/swap names are trusted only when the whole shape
        // matches it; on any other build frames are counted at EGL14.eglSwapBuffers instead.
        Method render = optionalMethod(base, "M", boolean.class), swap = optionalMethod(egl, "l");
        request = optionalMethod(base, "U", boolean.class);
        knownShape = "t0.a".equals(egl.getName()) && render != null && swap != null && request != null
                && swap.getReturnType() == boolean.class && "j".equals(eglOfService.getName());
        if (!knownShape) request = null;
        shapeReport = "shape=" + (knownShape ? "known" : "generic") + " service=" + serviceOfEngine.getName()
                + " engine=" + engineOfService.getName() + " egl=" + egl.getName() + "." + eglOfService.getName()
                + (request == null ? " frameRequest=unresolved" : "");
        for (var ctor : base.getDeclaredConstructors()) register(ctor, chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject(); main.post(() -> guarded(() -> capture(owner))); return result;
        });
        if (knownShape) register(render, chain -> {
            Engine engine;
            synchronized (engines) { engine = engines.get(chain.getThisObject()); }
            Drawing previous = drawing.get();
            SurfaceControl buffer = engine == null || engine.universal == null ? null
                    : (SurfaceControl) NativeReflection.get(engine.universal, "mBbqSurfaceControl");
            int bufferId = buffer == null || !buffer.isValid() ? -1 : ((Number) NativeReflection.call(buffer, "getLayerId")).intValue();
            drawing.set(new Drawing(engine, bufferId));
            try { return chain.proceed(); }
            finally { if (previous == null) drawing.remove(); else drawing.set(previous); }
        });
        if (!knownShape) {
            register(EGL14Swap.method(), chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result)) {
                    Object surface = chain.getArg(1);
                    main.post(() -> guarded(() -> {
                        Engine engine = engineForSurface(surface);
                        if (engine == null) return;
                        bind(engine);
                        if (!engine.valid()) return;
                        engine.swaps++; reporter.wallpaperFrames.incrementAndGet();
                        if (lease.id() >= 0) publish();
                    }));
                }
                return result;
            });
        } else register(swap, chain -> {
            Object result = chain.proceed(); Drawing sample = drawing.get(); Engine engine = sample == null ? null : sample.engine();
            if (engine != null && Boolean.TRUE.equals(result)
                    && get(eglOfService, engine.service) == chain.getThisObject()) {
                main.post(() -> guarded(() -> {
                    bind(engine);
                    if (!engine.valid() || sample.bufferId() <= 0
                            || ((Number) NativeReflection.call(engine.buffer, "getLayerId")).intValue() != sample.bufferId()) return;
                    engine.swaps++; reporter.wallpaperFrames.incrementAndGet();
                    if (lease.id() >= 0) publish();
                }));
            }
            return result;
        });
        for (String name : new String[]{"onSurfaceCreated", "onSurfaceDestroyed", "onSurfaceRedrawNeeded"}) {
            register(NativeReflection.method(universal, name, SurfaceHolder.class), chain -> {
                Object result = chain.proceed(); Object owner = chain.getThisObject();
                main.post(() -> guarded(() -> {
                    Object service = get(serviceOfEngine, owner);
                    Engine engine = capture(service);
                    if (engine == null) return;
                    engine.universal = owner;
                    if (name.equals("onSurfaceDestroyed")) {
                        engine.container = engine.buffer = null; engine.generation = ++generations;
                        engine.baseline = engine.swaps; publish();
                    } else { bind(engine); if (lease.id() >= 0) requestFrames(); }
                })); return result;
            });
        }
        register(NativeReflection.method(universal, "onVisibilityChanged", boolean.class), chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject(); boolean visible = (boolean) chain.getArg(0);
            main.post(() -> guarded(() -> {
                Engine engine = capture(get(serviceOfEngine, owner)); if (engine == null) return;
                engine.universal = owner; engine.visible = visible; bind(engine); reporter.entry.incrementAndGet();
                // Warm the existing desktop output when the native lock engine becomes visible.
                // Does not show any application or change native keyguard/engine visibility.
                if (visible && engine.which == 2) requestFrames();
                if (lease.id() >= 0) publish(); else reporter.publish();
            })); return result;
        });
        if (knownShape) for (var method : new Method[]{render, swap, request}) module.deoptimize(method);
        reporter.state = "loaded"; reporter.wallpaper = shapeReport;
        reporter.compat.on("壁纸出帧", knownShape ? "" : "通用：EGL14 计帧，无法主动请求出帧");
    }
    /** See {@link #platformMode}. */
    private void installPlatform(String why) throws Throwable {
        platformMode = true;
        Class<?> engineType = Class.forName("android.service.wallpaper.WallpaperService$Engine", false, loader);
        register(EGL14Swap.method(), chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) {
                Object surface = chain.getArg(1);
                main.post(() -> guarded(() -> {
                    Engine engine = engineForSurface(surface);
                    if (engine == null) return;
                    bind(engine);
                    if (!engine.valid()) return;
                    engine.swaps++; reporter.wallpaperFrames.incrementAndGet();
                    if (lease.id() >= 0) publish();
                }));
            }
            return result;
        });
        // The framework's own drivers of the subclass callbacks; their overloads vary by release.
        for (Method surface : com.zhuanz.flymemotion.core.Members.overloads(engineType, "updateSurface")) register(surface, chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject();
            main.post(() -> guarded(() -> {
                Engine engine = capture(owner); if (engine == null) return;
                bind(engine); if (lease.id() >= 0) requestFrames();
            })); return result;
        });
        for (Method visibility : com.zhuanz.flymemotion.core.Members.overloads(engineType, "doVisibilityChanged")) register(visibility, chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject(); boolean visible = (boolean) chain.getArg(0);
            main.post(() -> guarded(() -> {
                Engine engine = capture(owner); if (engine == null) return;
                engine.visible = visible; bind(engine); reporter.entry.incrementAndGet();
                if (lease.id() >= 0) publish(); else reporter.publish();
            })); return result;
        });
        for (Method detach : com.zhuanz.flymemotion.core.Members.overloads(engineType, "detach")) register(detach, chain -> {
            Object owner = chain.getThisObject(); Object result = chain.proceed();
            main.post(() -> guarded(() -> {
                Engine engine; synchronized (engines) { engine = engines.remove(owner); }
                if (engine != null) publish();
            })); return result;
        });
        reporter.state = "loaded";
        reporter.wallpaper = "通用壁纸引擎模式：" + why;
        reporter.compat.on("壁纸出帧", "通用引擎模式（" + why + "），无法主动请求出帧");
    }
    void attach(Context c) {
        context = c.getApplicationContext(); reporter.attach(context);
        if (c.getApplicationInfo() == null) return;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!SceneReceivers.fromPackage(c, this, "com.android.systemui")) return;
                    guarded(() -> receive(intent));
                }
            }, new IntentFilter(SceneProtocol.REQUEST), NativeSceneChannel.PERMISSION, main, Context.RECEIVER_EXPORTED);
            SceneReceivers.revoke(context, main, reporter, this::end);
            reporter.wallpaper = "现有 ImageEngine 监听就绪，等待原生 Surface / EGL swap"; reporter.publish();
        } catch (Throwable t) { end(); reporter.fail(t); }
    }
    private Engine capture(Object service) {
        if (service == null || !isImage(service)) return null;
        synchronized (engines) {
            Engine existing = engines.get(service); if (existing != null) return existing;
            int identity = role(service);
            if (identity != 1 && identity != 2 && identity != 3) return null;
            EngineNames.resolve(service); // HMC's type-safe renderer/pending-flag discovery; read-only.
            Engine engine = new Engine(service, identity); engines.put(service, engine);
            engine.universal = universalOf(service); bind(engine); return engine;
        }
    }
    private boolean isImage(Object service) {
        if (platformMode) return service instanceof android.service.wallpaper.WallpaperService.Engine;
        for (Class<?> type = service.getClass(); type != null; type = type.getSuperclass())
            if (BASE.equals(type.getName())) return true;
        return false;
    }
    private int role(Object service){
        // This APK's lock stream renderer inherits ImageEngineImpl directly; unlike
        // KeyguardImageEngineImpl it has no s() getter. Its own show/hideKeyguardWallpaper
        // implementation identifies the lock-only producer. Do not call a missing method.
        if(STREAM_LOCK.equals(service.getClass().getName()))return 2;
        if(knownShape)return ((Number)NativeReflection.call(service,"s")).intValue();
        // Any other build: the platform's own flags on the hosting engine (FLAG_SYSTEM=1, FLAG_LOCK=2).
        // A build without getWallpaperFlags has one shared engine for both, i.e. 3.
        Object engine=universalOf(service);
        if(engine==null)return 0;
        Object flags=NativeReflection.optCall(engine,"getWallpaperFlags");
        return flags instanceof Number n?n.intValue():3;
    }
    private Object universalOf(Object service){ return platformMode?service:get(engineOfService,service); }
    private Object serviceOf(Object universal){ return platformMode?universal:get(serviceOfEngine,universal); }
    /** The engine owning this EGLSurface: in its own fields or one object below them. */
    private static boolean holdsSurface(Object owner,Object surface,int depth){
        if(owner==null||surface==null)return false;
        for(Class<?> k=owner.getClass();k!=null&&k!=Object.class;k=k.getSuperclass()){
            if(k.getName().startsWith("android.")||k.getName().startsWith("java."))break;
            for(var f:k.getDeclaredFields()){
                if(java.lang.reflect.Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive())continue;
                try{
                    f.setAccessible(true);Object value=f.get(owner);
                    if(value==null)continue;
                    if(f.getType()==android.opengl.EGLSurface.class){if(surface.equals(value))return true;}
                    else if(depth>0&&holdsSurface(value,surface,depth-1))return true;
                }catch(ReflectiveOperationException|RuntimeException ignored){}
            }
        }
        return false;
    }
    private Engine engineForSurface(Object surface){
        synchronized(engines){
            for(Engine e:engines.values()){
                if(platformMode){if(holdsSurface(e.service,surface,1))return e;continue;}
                Object helper=get(eglOfService,e.service);
                if(helper!=null&&surface!=null&&surface.equals(fieldValueOfType(helper,android.opengl.EGLSurface.class)))return e;
            }
        }
        return null;
    }
    private static Object get(java.lang.reflect.Field f,Object owner){
        try{return owner==null?null:f.get(owner);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    private static java.lang.reflect.Field fieldWhere(Class<?> type,java.util.function.Predicate<java.lang.reflect.Field> test){
        for(Class<?> k=type;k!=null&&k!=Object.class;k=k.getSuperclass())
            for(var f:k.getDeclaredFields())
                if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())&&test.test(f)){f.setAccessible(true);return f;}
        return null;
    }
    private static boolean declares(Class<?> type,Class<?> member){
        return fieldWhere(type,f->f.getType()==member)!=null;
    }
    private static Object fieldValueOfType(Object owner,Class<?> member){
        var f=fieldWhere(owner.getClass(),x->x.getType()==member);
        return f==null?null:get(f,owner);
    }
    private static Method optionalMethod(Class<?> type,String name,Class<?>... args){
        try{Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}catch(NoSuchMethodException e){return null;}
    }
    private void bind(Engine e) {
        int actualRole = role(e.service);
        if (actualRole != 1 && actualRole != 2 && actualRole != 3) { e.container = e.buffer = null; return; }
        if (actualRole != e.which) { e.which = actualRole; e.generation = ++generations; e.baseline = e.swaps; }
        if (e.universal == null) e.universal = universalOf(e.service);
        if (e.universal == null) return;
        // EngineService can be replaced while the same UniversalEngine/BLAST layer survives.
        // A retained old image renderer must never acknowledge the selected engine's buffer.
        if (serviceOf(e.universal) != e.service
                || (boolean) NativeReflection.call(e.universal, "isPreview")) {
            if (e.container != null || e.buffer != null) { e.generation = ++generations; e.baseline = e.swaps; }
            e.container = e.buffer = null; return;
        }
        SurfaceControl container = (SurfaceControl) NativeReflection.get(e.universal, "mSurfaceControl");
        SurfaceControl buffer = (SurfaceControl) NativeReflection.get(e.universal, "mBbqSurfaceControl");
        if (!same(container, e.container) || !same(buffer, e.buffer)) {
            e.container = container; e.buffer = buffer; e.generation = ++generations; e.baseline = e.swaps;
        }
    }
    private static boolean same(SurfaceControl a, SurfaceControl b) {
        return a == b || a != null && b != null && a.isValid() && b.isValid()
                && (boolean) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class, "isSameSurface", SurfaceControl.class), a, b);
    }
    private void receive(Intent intent) {
        if (!"loaded".equals(reporter.state)) return;
        long token = intent.getLongExtra("token", -1); String command = intent.getStringExtra("command");
        if ("end".equals(command)) { if (token == lease.id()) end(); return; }
        if ("begin".equals(command)) {
            if (!lease.begin(token, SystemClock.uptimeMillis())) return;
            synchronized (engines) { for (Engine e : engines.values()) { bind(e); e.baseline = e.swaps; } }
        } else if (!"renew".equals(command) || !lease.renew(token, SystemClock.uptimeMillis())) return;
        reporter.sceneSession = token; main.removeCallbacks(keepAlive); keepAlive.run(); publish();
    }
    private void requestFrames() {
        synchronized (engines) {
            for (Engine e : engines.values()) {
                bind(e);
                if (e.valid() && request != null) NativeReflection.invoke(request, e.service, true);
            }
        }
    }



    private void publish() {
        boolean home = false, lock = false; long homeGeneration = 0, lockGeneration = 0;
        StringBuilder detail = new StringBuilder();
        ArrayList<android.os.Bundle> frames = new ArrayList<>();
        synchronized (engines) {
            for (Engine e : engines.values()) {
                bind(e);
                if ((e.which & 1) != 0 && e.valid() && e.generation >= homeGeneration) { home = e.drawn(); homeGeneration = e.generation; }
                if ((e.which & 2) != 0 && e.valid() && e.generation >= lockGeneration) { lock = e.drawn(); lockGeneration = e.generation; }
                if (e.valid()) {
                    android.os.Bundle frame = new android.os.Bundle();
                    frame.putInt("which", e.which); frame.putLong("generation", e.generation); frame.putBoolean("drawn", e.drawn());
                    frame.putLong("drawSequence",e.swaps);
                    frame.putInt("containerLayer",((Number)NativeReflection.call(e.container,"getLayerId")).intValue());
                    frame.putInt("bufferLayer",((Number)NativeReflection.call(e.buffer,"getLayerId")).intValue());
                    frame.putBinder("client", ((android.os.IInterface) NativeReflection.get(e.universal, "mWindow")).asBinder());
                    frames.add(frame);
                }
                if (detail.length() < 350) detail.append("; ").append(e.which).append(':')
                        .append(e.service.getClass().getSimpleName()).append(" valid=").append(e.valid())
                        .append(knownShape ? " surfaceReady=" + NativeReflection.bool(e.service, "h") : "")
                        .append(" visible=").append(e.visible).append(" drawn=").append(e.drawn());
            }
        }
        reporter.sceneSession = lease.id(); reporter.surfaceGeneration = homeGeneration;
        reporter.sceneReadiness = "session=" + lease.id() + " homeGen=" + homeGeneration + " homeSwap=" + home
                + " lockGen=" + lockGeneration + " lockSwap=" + lock + detail;
        if (reporter.sceneReadiness.equals(lastPublished)) return;
        lastPublished = reporter.sceneReadiness;
        reporter.wallpaper = "使用现有壁纸引擎输出；未替换封面或纹理"; reporter.publish();
        if (context == null || lease.id() < 0) return;
        long publishedSequence = ++statusSequence;
        for (String pkg : new String[]{"android", "com.android.systemui"})
            context.sendBroadcast(new Intent(SceneProtocol.WALLPAPER_STATUS).setPackage(pkg)
                    .putExtra("token", lease.id()).putExtra("statusSequence",publishedSequence).putExtra("homeDrawn", home).putExtra("lockDrawn", lock)
                    .putExtra("homeGeneration", homeGeneration).putExtra("lockGeneration", lockGeneration)
                    .putParcelableArrayListExtra("frames", frames),
                    NativeSceneChannel.PERMISSION, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private void end() {
        lease.clear(); main.removeCallbacks(keepAlive); reporter.sceneSession = -1;
        lastPublished = "";
        reporter.sceneReadiness = "壁纸绘制会话已撤销，EGL 由原生延时回收"; reporter.publish();
    }
    private void guarded(Runnable work) {
        try { work.run(); } catch (Throwable t) { end(); reporter.fail(t); }
    }
    private void register(java.lang.reflect.Executable method, XposedInterface.Hooker callback) {
        handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(callback));
        reporter.hooks = handles.size();
    }
}
