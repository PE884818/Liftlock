package com.zhuanz.flymemotion.hook;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

final class SettingsHooks {
    private static final String KEY = "flyme_motion_lockscreen";
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private boolean failed;
    SettingsHooks(MotionModule module, ClassLoader loader, RuntimeReporter reporter) {
        this.module = module; this.loader = loader; this.reporter = reporter;
    }
    void install() throws ReflectiveOperationException {
        // The entry is a convenience: on a Settings build without this page the manager app is
        // still the way in, so a miss is reported, never a failure.
        Class<?> page = com.zhuanz.flymemotion.core.Members.load(loader, "com.android.settings.AodAndLockScreenSettings");
        Class<?> preference = com.zhuanz.flymemotion.core.Members.load(loader, "androidx.preference.Preference");
        Class<?> group = com.zhuanz.flymemotion.core.Members.load(loader, "androidx.preference.PreferenceGroup");
        if (page == null || preference == null || group == null) {
            reporter.compat.off("设置入口", (page == null ? "AodAndLockScreenSettings" : "androidx.preference") + " not found");
            return;
        }
        // HyperOS styling when miuix is present, else the host's plain Preference.
        Class<?> textPreference = com.zhuanz.flymemotion.core.Members.load(loader, "miuix.preference.TextPreference");
        var constructor = (textPreference != null ? textPreference : preference).getConstructor(Context.class, AttributeSet.class);
        if (textPreference == null) reporter.compat.note("设置入口", "无 miuix TextPreference，使用普通样式");
        Method find = NativeReflection.method(group, "findPreference", CharSequence.class);
        Method add = NativeReflection.method(group, "addPreference", preference);
        module.hook(NativeReflection.method(page, "onResume"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (failed) return result;
                    try {
                        Object fragment = chain.getThisObject();
                        Context context = (Context) NativeReflection.call(fragment, "getContext");
                        Object screen = NativeReflection.call(fragment, "getPreferenceScreen");
                        if (context == null || screen == null || NativeReflection.invoke(find, screen, KEY) != null) return result;
                        // Use the host's Preference and resources, so HyperOS retains its own item styling.
                        Object target = NativeReflection.invoke(find, screen, "others_category");
                        if (target == null) target = screen;
                        Object item = constructor.newInstance(context, null);
                        NativeReflection.invoke(NativeReflection.method(preference, "setKey", String.class), item, KEY);
                        NativeReflection.invoke(NativeReflection.method(preference, "setTitle", CharSequence.class), item, "Flyme 手感");
                        NativeReflection.invoke(NativeReflection.method(preference, "setSummary", CharSequence.class), item, "锁屏跟手、回弹与原生模糊");
                        NativeReflection.invoke(NativeReflection.method(preference, "setOrder", int.class), item, 90);
                        Intent intent = new Intent().setClassName("com.zhuanz.flymemotion", "com.zhuanz.flymemotion.ManagerActivity")
                                .putExtra("page", "lockscreen");
                        NativeReflection.invoke(NativeReflection.method(preference, "setIntent", Intent.class), item, intent);
                        NativeReflection.invoke(add, target, item);
                        reporter.entry.incrementAndGet(); reporter.publish();
                    } catch (Throwable t) { failed = true; reporter.fail(t); }
                    return result;
                });
        reporter.hooks++;
        reporter.compat.on("设置入口", "");
    }
}
