package com.zhuanz.flymemotion.hook;

import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.LinkedHashSet;

/** Resource names and geometry only. Never reads text, notifications, credentials or pixels. */
final class NativeSceneDiagnostics {
    private NativeSceneDiagnostics() {}
    static String layers(View[] views) {
        StringBuilder out = new StringBuilder();
        for (View view : new LinkedHashSet<>(java.util.Arrays.asList(views))) {
            if (view == null) continue;
            if (out.length() > 0) out.append("; ");
            out.append(name(view)).append('[').append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" vis=").append(view.getVisibility()).append(" a=").append(view.getAlpha())
                    .append(" y=").append(view.getTranslationY()).append(']');
            if (view.getClipBounds() != null) out.append(" crop=").append(view.getClipBounds());
            ViewParent parent = view.getParent();
            for (int depth = 0; parent instanceof View p && depth < 4; depth++, parent = p.getParent()) {
                out.append(" < ").append(name(p));
                if (p instanceof ViewGroup group) out.append(group.getClipChildren() ? "{clip}" : "{open}");
            }
            if (out.length() >= 3800) break;
        }
        return out.substring(0, Math.min(out.length(), 4096));
    }
    private static String name(View view) {
        String id = "no-id";
        try { if (view.getId() != View.NO_ID) id = view.getResources().getResourceEntryName(view.getId()); }
        catch (RuntimeException ignored) {}
        return view.getClass().getSimpleName() + "#" + id;
    }
    static String wallpaper(ClassLoader loader) {
        SurfaceControl lock = null, home = null;
        try {
            Class<?> wm = Class.forName("android.view.WindowManagerGlobal", false, loader);
            Object global = NativeReflection.invoke(NativeReflection.method(wm, "getInstance"), null);
            var getter = NativeReflection.method(wm, "getWallpaperByShowWhenLocked", int.class, boolean.class);
            Object lockTarget = NativeReflection.invoke(getter, global, 0, true);
            Object homeTarget = NativeReflection.invoke(getter, global, 0, false);
            if (lockTarget != null) lock = (SurfaceControl) NativeReflection.get(lockTarget, "leash");
            if (homeTarget != null) home = (SurfaceControl) NativeReflection.get(homeTarget, "leash");
            NativeReflection.method(Class.forName("android.view.ViewRootImpl", false, loader),
                    "setKeyguardMatrixAndAlpha", SurfaceControl.class, float[].class);
            return "原生壁纸接口：锁屏=" + valid(lock) + "，桌面=" + valid(home)
                    + "，帧同步矩阵接口存在；仅读取，尚未接入壁纸位移或应用透出";
        } catch (Throwable t) {
            return "独立壁纸接口读取失败：" + t.getClass().getSimpleName() + "；手势保持可用，壁纸与应用透出未完成";
        } finally {
            // Binder returns local references owned by this lookup; do not remove native surfaces.
            if (lock != null) lock.release();
            if (home != null && home != lock) home.release();
        }
    }
    private static String valid(SurfaceControl surface) { return surface != null && surface.isValid() ? "有效" : "缺失"; }
}
