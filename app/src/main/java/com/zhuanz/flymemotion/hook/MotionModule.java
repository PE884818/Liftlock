package com.zhuanz.flymemotion.hook;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Modern API 101 entry: SystemUI, Settings and the explicitly authorized framework scene. */
public final class MotionModule extends XposedModule {
    private String process;
    private boolean initialized;
    @Override public void onModuleLoaded(ModuleLoadedParam param) { process = param.getProcessName(); }
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (initialized) return;
        initialized = true;
        RuntimeReporter reporter = new RuntimeReporter("android");
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = NativeReflection.invoke(NativeReflection.method(activityThread, "currentActivityThread"), null);
            reporter.attach((Context) NativeReflection.call(thread, "getSystemContext"));
        } catch (Throwable t) { log(Log.WARN, "FlymeMotion", "Early framework receipt context unavailable", t); }
        try { new SystemServerSceneHooks(this, param.getClassLoader(), reporter).install(); }
        catch (Throwable t) {
            reporter.state = "unsupported"; reporter.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            reporter.publish();
            log(Log.ERROR, "FlymeMotion", "Framework scene unavailable; keep native behavior", t);
        }
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (initialized || !param.isFirstPackage() || !pkg.equals(process)
                || !(pkg.equals("com.android.systemui") || pkg.equals("com.android.settings") || pkg.equals("com.miui.miwallpaper"))) return;
        initialized = true;
        RuntimeReporter reporter = new RuntimeReporter(pkg);
        ClassLoader loader = param.getClassLoader();
        try {
            WallpaperHooks wallpaper = pkg.equals("com.miui.miwallpaper") ? new WallpaperHooks(this, loader, reporter) : null;
            String appName = param.getApplicationInfo().className;
            Class<?> app = appName == null ? Application.class : Class.forName(appName, false, loader);
            hook(NativeReflection.method(app, "onCreate")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            reporter.attach((Context) chain.getThisObject());
                            if (wallpaper != null) wallpaper.attach((Context) chain.getThisObject());
                        }
                        catch (RuntimeException e) { reporter.fail(e); }
                        return result;
                    });
            if (pkg.equals("com.android.systemui")) {
                SystemUiHooks hooks = new SystemUiHooks(this, loader, reporter);
                hooks.install();
            } else if (wallpaper != null) wallpaper.install();
            else new SettingsHooks(this, loader, reporter).install();
            reporter.state = "loaded";
            log(Log.INFO, "FlymeMotion", pkg + " registered " + reporter.hooks + " hooks for " + Build.VERSION.INCREMENTAL);
        } catch (Throwable t) {
            reporter.state = "unsupported"; reporter.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            log(Log.ERROR, "FlymeMotion", "Registration failed; keep native behavior", t);
        }
    }
}
