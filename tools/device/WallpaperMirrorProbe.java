package com.zhuanz.flymemotion.probe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Root app_process diagnostic. Every created node uses an off-display layer stack.
 * Never changes a native wallpaper's parent, alpha, visibility, matrix, crop or buffer.
 * No input, auth, keyguard state, process restart, preference or task changes.
 */
public final class WallpaperMirrorProbe {
    private static final int OFF_DISPLAY_STACK = 100_731;
    private static final List<SurfaceControl> owned = new ArrayList<>();
    private static Method crop, mirror, capture, synchronousApply;

    private static Object call(Object receiver, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = receiver.getClass().getDeclaredMethod(name, signature); method.setAccessible(true);
        return method.invoke(receiver, args);
    }
    private static Object staticCall(String type, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = Class.forName(type).getDeclaredMethod(name, signature); method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static SurfaceControl node(String name, SurfaceControl parent, boolean colored) throws Exception {
        SurfaceControl.Builder builder = new SurfaceControl.Builder().setName(name).setHidden(true).setParent(parent);
        call(builder, colored ? "setColorLayer" : "setContainerLayer", new Class<?>[0]);
        SurfaceControl surface = builder.build(); owned.add(surface); return surface;
    }
    private static void commit(SurfaceControl.Transaction transaction) throws Exception {
        synchronousApply.invoke(transaction, true);
        // Fence timeout/capture is reported separately; avoid assuming enqueue means a frame exists.
        Thread.sleep(70);
    }
    private static SurfaceControl mirrored(SurfaceControl source, SurfaceControl parent) throws Exception {
        SurfaceControl result = (SurfaceControl) mirror.invoke(null, source); owned.add(result);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.reparent(result, parent).setScale(result, 1, 1).setPosition(result, 0, 0)
                    .setAlpha(result, 1).setVisibility(result, true); commit(t);
        }
        return result;
    }
    private static Bitmap image(SurfaceControl source, Rect bounds, float scale) throws Exception {
        Object result = capture.invoke(null, source, bounds, scale);
        if (result == null) throw new IllegalStateException("captureLayers returned null");
        Bitmap hardware = (Bitmap) call(result, "asBitmap", new Class<?>[0]);
        if (hardware == null) throw new IllegalStateException("captureLayers bitmap unavailable");
        Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle();
        ((android.hardware.HardwareBuffer) call(result, "getHardwareBuffer", new Class<?>[0])).close();
        return software;
    }
    private static JSONObject pixels(SurfaceControl source, int expected) throws Exception {
        Bitmap bitmap = image(source, new Rect(0, 0, 64, 64), 1);
        try {
            int center = bitmap.getPixel(32, 32); int matching = 0;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) if (bitmap.getPixel(x, y) == expected) matching++;
            return new JSONObject().put("center", String.format("%08x", center)).put("matching", matching)
                    .put("pixels", 4096).put("passed", matching == 4096);
        } finally { bitmap.recycle(); }
    }
    private static String reason(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getName() + ": " + String.valueOf(error.getMessage());
    }
    public static void main(String[] args) {
        JSONObject report = new JSONObject();
        try {
            android.os.Looper.prepareMainLooper();
            if (!"myron".equals(Build.DEVICE) || Build.VERSION.SDK_INT != 37
                    || !"OS4.0.0.30.XPMCNXM".equals(Build.VERSION.INCREMENTAL))
                throw new IllegalStateException("This probe is pinned to the physical myron build");
            report.put("device", Build.DEVICE).put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                    .put("uid", android.os.Process.myUid()).put("offDisplayLayerStack", OFF_DISPLAY_STACK);
            if (android.os.Process.myUid() != 0 && android.os.Process.myUid() != 10227)
                throw new IllegalStateException("Only Root or the verified SystemUI UID may run this off-display probe");
            mirror = SurfaceControl.class.getDeclaredMethod("mirrorSurface", SurfaceControl.class); mirror.setAccessible(true);
            crop = SurfaceControl.Transaction.class.getDeclaredMethod("setCrop", SurfaceControl.class, Rect.class); crop.setAccessible(true);
            synchronousApply = SurfaceControl.Transaction.class.getDeclaredMethod("apply", boolean.class); synchronousApply.setAccessible(true);
            capture = Class.forName("android.window.ScreenCaptureInternal").getDeclaredMethod("captureLayers", SurfaceControl.class, Rect.class, float.class);
            capture.setAccessible(true);
            SurfaceControl root = node("FlymeProbe-OffDisplay", null, false);
            SurfaceControl producer = node("FlymeProbe-OwnedProducer", root, false);
            SurfaceControl color = node("FlymeProbe-OwnedColor", producer, true);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                // Set stack before showing a root. This fixture never joins display stack 0.
                call(t, "setLayerStack", new Class<?>[]{SurfaceControl.class, int.class}, root, OFF_DISPLAY_STACK);
                crop.invoke(t, root, new Rect(0, 0, 64, 64)); crop.invoke(t, color, new Rect(0, 0, 64, 64));
                call(t, "setColor", new Class<?>[]{SurfaceControl.class, float[].class}, color, new float[]{1, 0, 0});
                t.setVisibility(root, true).setVisibility(producer, true).setVisibility(color, true); commit(t);
            }
            report.put("fixture", pixels(producer, Color.RED));
            SurfaceControl copy = mirrored(producer, root);
            report.put("mirrorAttached", pixels(copy, Color.RED));
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { t.reparent(producer, null); commit(t); }
            report.put("mirrorDetached", pixels(copy, Color.RED));
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                call(t, "setColor", new Class<?>[]{SurfaceControl.class, float[].class}, color, new float[]{0, 0, 1}); commit(t);
            }
            report.put("mirrorLiveUpdate", pixels(copy, Color.BLUE));
            SurfaceControl background = node("FlymeProbe-ActualReturnBuffer", root, true);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                call(t, "setColor", new Class<?>[]{SurfaceControl.class, float[].class}, background, new float[]{0, 1, 0});
                crop.invoke(t, background, new Rect(0, 0, 64, 64));
                t.setLayer(background, -1).setLayer(copy, 1).setVisibility(background, true); commit(t);
            }
            // Parcelable is the same SurfaceControl handle transfer used by the framework reply.
            android.os.Parcel parcel = android.os.Parcel.obtain();
            copy.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            SurfaceControl transferred = SurfaceControl.CREATOR.createFromParcel(parcel); parcel.recycle(); owned.add(transferred);
            org.json.JSONArray stages = new org.json.JSONArray();
            for (float progress : new float[]{1, .8f, .5f, .1f, 0}) {
                float alpha = Math.max(0, Math.min(1, (progress - .02f) / .78f));
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    crop.invoke(t, transferred, new Rect(0, 0, 64, Math.max(1, Math.round(64 * progress))));
                    t.setAlpha(transferred, alpha); commit(t);
                }
                Bitmap composed = image(root, new Rect(0, 0, 64, 64), 1);
                try {
                    int expectedTop = progress == 0 ? Color.GREEN : Color.rgb(0, Math.round(255 * (1-alpha)), Math.round(255 * alpha));
                    int sampleY = progress == 0 ? 0 : Math.max(0, (int) (64 * progress / 2));
                    int actualTop = composed.getPixel(32, sampleY), actualBottom = composed.getPixel(32, 63);
                    boolean topPass = Math.abs(Color.green(actualTop) - Color.green(expectedTop)) <= 2
                            && Math.abs(Color.blue(actualTop) - Color.blue(expectedTop)) <= 2;
                    boolean bottomPass = progress == 1 ? actualBottom == Color.BLUE : actualBottom == Color.GREEN;
                    stages.put(new JSONObject().put("p", progress).put("alpha", alpha)
                            .put("top", String.format("%08x", actualTop)).put("bottom", String.format("%08x", actualBottom))
                            .put("passed", topPass && bottomPass));
                } finally { composed.recycle(); }
            }
            report.put("cropFadeOverReturnBuffer", stages);
            report.put("sourceGeometryUnchanged", pixels(producer, Color.BLUE));
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                // Exactly the production ordering: original restored, mirror removed in one commit.
                t.reparent(producer, root).setLayer(producer, 1).setVisibility(copy, false).reparent(copy, null); commit(t);
            }
            report.put("atomicRollback", pixels(root, Color.BLUE));
            // Native wallpaper is read only. Do not infer a displayed scene from this capture.
            SurfaceControl wallpaperRoot = node("FlymeProbe-OffDisplayWallpaper", null, false);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                call(t, "setLayerStack", new Class<?>[]{SurfaceControl.class, int.class}, wallpaperRoot, OFF_DISPLAY_STACK);
                t.setVisibility(wallpaperRoot, true); commit(t);
            }
            Object global = staticCall("android.view.WindowManagerGlobal", "getInstance", new Class<?>[0]);
            try {
                staticCall("android.view.WindowManagerGlobal", "getWindowManagerService", new Class<?>[0]);
            } catch (Throwable bootstrap) {
                // app_process lacks the application shared-memory setup. This can fail after
                // caching IWindowManager; subsequent read-only queries then use that binder.
                report.put("standaloneBootstrapWarning", reason(bootstrap));
            }
            for (boolean locked : new boolean[]{true, false}) {
                JSONObject nativeReport = new JSONObject();
                try {
                    Object target = call(global, "getWallpaperByShowWhenLocked", new Class<?>[]{int.class, boolean.class}, 0, locked);
                    SurfaceControl nativeSource = target == null ? null : (SurfaceControl) call(target, "getLeash", new Class<?>[0]);
                    nativeReport.put("valid", nativeSource != null && nativeSource.isValid());
                    if (nativeSource != null && nativeSource.isValid()) {
                        SurfaceControl nativeCopy = mirrored(nativeSource, wallpaperRoot);
                        Bitmap original = image(nativeSource, new Rect(0, 0, 1200, 2608), .05f);
                        Bitmap copied = image(nativeCopy, new Rect(0, 0, 1200, 2608), .05f);
                        try {
                            int nontransparent = 0, different = 0;
                            int w = Math.min(original.getWidth(), copied.getWidth()), h = Math.min(original.getHeight(), copied.getHeight());
                            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                                int p = copied.getPixel(x, y); if (Color.alpha(p) > 0) nontransparent++;
                                if (p != original.getPixel(x, y)) different++;
                            }
                            nativeReport.put("width", w).put("height", h).put("nontransparent", nontransparent)
                                    .put("differentFromSource", different).put("pixels", w * h);
                        } finally { original.recycle(); copied.recycle(); }
                        nativeSource.release();
                    }
                } catch (Throwable e) { nativeReport.put("error", reason(e)); }
                report.put(locked ? "lockWallpaper" : "homeWallpaper", nativeReport);
            }
            report.put("completed", true);
        } catch (Throwable e) {
            try { report.put("completed", false).put("error", reason(e)); } catch (Exception ignored) {}
        } finally {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                for (SurfaceControl surface : owned) if (surface != null && surface.isValid()) t.reparent(surface, null).setVisibility(surface, false);
                t.apply();
            } catch (Throwable ignored) {}
            for (SurfaceControl surface : owned) if (surface != null) surface.release();
            System.out.println("FLYME_MIRROR_PROBE " + report);
            System.exit(0);
        }
    }
}
