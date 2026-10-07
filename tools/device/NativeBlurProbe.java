package com.zhuanz.flymemotion.probe;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated, unattached ViewRoot: validates the target OEM Drawable ABI and our real sheet.
 * No window is added, no Surface joins a display, no input or native scene state is changed.
 * Position callbacks are supplied explicitly; this does not prove real RenderThread or pixels.
 */
public final class NativeBlurProbe {
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method m = type.getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Constructor<?> constructor(Class<?> type, Class<?>... args) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor(args); c.setAccessible(true); return c;
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalStateException(reason);
    }
    public static void main(String[] args) {
        JSONObject report = new JSONObject();
        try {
            require("myron".equals(Build.DEVICE) && Build.VERSION.SDK_INT == 37
                    && "OS4.0.0.30.XPMCNXM".equals(Build.VERSION.INCREMENTAL), "Unexpected build");
            Looper.prepareMainLooper();
            // app_process does not receive the normal zygote ApplicationSharedMemory handoff.
            // Allocate only this diagnostic process's memory; never write the system instance.
            Class<?> memory = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            Object localMemory = method(memory, "create").invoke(null);
            method(memory, "setInstance", memory).invoke(null, localMemory);
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = method(activityThread, "systemMain").invoke(null);
            Context context = (Context) method(activityThread, "getSystemContext").invoke(thread);
            Class<?> rootType = Class.forName("android.view.ViewRootImpl");
            // Avoid registration with the phone's WindowSession/DisplayManager. A zeroed root
            // needs only a Context and native Aggregator for this unattached coordinate probe.
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeType.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object root = method(unsafeType, "allocateInstance", Class.class).invoke(unsafe, rootType);
            Field rootContext = rootType.getDeclaredField("mContext"); rootContext.setAccessible(true); rootContext.set(root, context);
            Class<?> aggregatorType = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable$Aggregator");
            Object nativeAggregator = constructor(aggregatorType, rootType).newInstance(root);
            Field rootAggregator = rootType.getDeclaredField("mBlurRegionAggregator"); rootAggregator.setAccessible(true); rootAggregator.set(root, nativeAggregator);
            FrameLayout view = new FrameLayout(context);
            view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2608, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 1200, 2608);
            Class<?> sheetType = Class.forName("com.zhuanz.flymemotion.hook.NativeBlurSheet");
            Object sheet = constructor(sheetType, ClassLoader.class).newInstance(NativeBlurProbe.class.getClassLoader());
            method(sheetType, "begin", View.class, Object.class).invoke(sheet, view, root);
            require(!view.getClipChildren(), "Root clipped the independently positioned blur sheet");
            view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2608, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 1200, 2608);
            Class<?> frameType = Class.forName("com.zhuanz.flymemotion.core.MotionFrame");
            Method sample = method(frameType, "sample", float.class, float.class, float.class, boolean.class, float.class);
            Method apply = method(sheetType, "apply", frameType, int.class, int.class);
            Drawable drawable = (Drawable) field(sheet, "drawable");
            Object aggregator = field(drawable, "mAggregator");
            Method copy = method(aggregator.getClass(), "getBlurRegionsCopyForRT");
            Class<?> positionType = Class.forName("android.graphics.RenderNode$PositionUpdateListener");
            Method position = positionType.getMethod("positionChanged", long.class, int.class, int.class, int.class, int.class);
            JSONArray samples = new JSONArray(); long frameNumber = 1;
            for (float p : new float[]{1, .8f, .5f, .1f, 0}) {
                Object frame = sample.invoke(null, 2608 * p, -400f, 2608f, true, 1f);
                float rootY = -2608 * (1 - p) + 73; // Deliberate native baseline/overshoot.
                view.setTranslationY(rootY);
                apply.invoke(sheet, frame, p > 0 && p < 1 ? 64 : 0, 0x33000000);
                View child = (View) field(sheet, "sheet");
                int top = Math.round(rootY + child.getTranslationY()), bottom = top + 2608;
                position.invoke(field(drawable, "mPositionUpdateListener"), frameNumber, 0, top, 1200, bottom);
                Object regions = copy.invoke(aggregator);
                ArrayList<Integer> lengths = new ArrayList<>(); ArrayList<Object> modes = new ArrayList<>();
                float[][] abi = (float[][]) method(aggregator.getClass(), "getBlurRegionsForFrameLocked",
                        long.class, regions.getClass(), boolean.class, java.util.List.class, java.util.List.class)
                        .invoke(aggregator, frameNumber++, regions, true, lengths, modes);
                int expectedHeight = Math.round(2608 * p);
                require(Math.abs(bottom - expectedHeight) <= 1, "Blur screen bottom ignored root compensation");
                if (p > 0 && p < 1) {
                    require(abi.length == 1 && abi[0].length == 27, "Wrong OEM region ABI");
                    require(abi[0][3] == top && abi[0][5] == bottom, "Position callback coordinates lost");
                    require(abi[0][14] == 0 && abi[0][15] == 0 && abi[0][16] == 1200
                            && abi[0][17] == expectedHeight, "Native crop escaped visible coverage");
                } else require(abi.length == 0, "Endpoint left active blur");
                samples.put(new JSONObject().put("p", p).put("screenTop", top).put("screenBottom", bottom)
                        .put("crop", ((Rect) field(drawable, "mCropRect")).toShortString()).put("regions", abi.length));
            }
            method(sheetType, "release").invoke(sheet);
            require(view.getChildCount() == 0 && !(boolean) method(aggregator.getClass(), "hasRegions").invoke(aggregator),
                    "Handoff retained blur or view");
            require(view.getClipChildren(), "Handoff did not restore native clipping");
            FrameLayout depth = new FrameLayout(context); View clock = new View(context);
            view.addView(depth, new FrameLayout.LayoutParams(1200, 2608));
            view.addView(clock, new FrameLayout.LayoutParams(1200, 2608));
            view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2608, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 1200, 2608);
            view.setTranslationY(0); depth.setTranslationY(7); depth.setAlpha(.8f); depth.setElevation(.2f); clock.setElevation(.1f);
            Class<?> contentType = Class.forName("com.zhuanz.flymemotion.LockscreenPose");
            Object content = contentType.getConstructor().newInstance();
            Class<?> rolesType = Class.forName("com.zhuanz.flymemotion.WallpaperLayerPose");
            Object roles = rolesType.getConstructor().newInstance();
            Object half = sample.invoke(null, 1304f, -400f, 2608f, true, 1f);
            method(contentType, "apply", float.class, View[].class).invoke(content, -1304f, new View[]{view});
            method(rolesType, "apply", frameType, contentType, View[].class).invoke(roles, half, content, new View[]{depth});
            require(Math.abs(view.getTranslationY() + depth.getTranslationY() - 7) < .001f, "Depth moved with clock");
            float alpha = ((Number) method(frameType, "wallpaperAlpha").invoke(half)).floatValue();
            require(Math.abs(depth.getAlpha() - .8f * alpha) < .001f, "Depth alpha differs from wallpaper");
            require(depth.getElevation() == .2f && clock.getElevation() == .1f && depth.getParent() == view, "Depth order changed");
            require(depth.getClipBounds().bottom <= 1304, "Depth crop differs from wallpaper viewport");
            method(rolesType, "restore").invoke(roles); method(contentType, "restore").invoke(content);
            require(depth.getTranslationY() == 7 && depth.getAlpha() == .8f && depth.getClipBounds() == null
                    && view.getClipChildren(), "Depth rollback differs from original scene");
            Class<?> regionalType = Class.forName("com.zhuanz.flymemotion.hook.NativeRegionalBlurLease");
            Object regional = constructor(regionalType, ClassLoader.class).newInstance(NativeBlurProbe.class.getClassLoader());
            method(rootType, "setWallpaperBlur", int.class).invoke(root, 21);
            method(regionalType, "begin", Object.class, Object.class).invoke(regional, root, null);
            require((int) field(root, "mDeductedblur") == 0, "Regional ownership kept full wallpaper blur");
            require((int) method(regionalType, "wallpaperRequest", Object.class, int.class).invoke(regional, root, 73) == 0,
                    "Owned root accepted a competing global wallpaper blur");
            require((int) method(regionalType, "wallpaperRequest", Object.class, int.class).invoke(regional, new Object(), 91) == 91,
                    "Regional lease changed an unrelated root");
            method(regionalType, "release").invoke(regional);
            require((int) field(root, "mDeductedblur") == 73, "Release restored stale wallpaper blur intent");
            report.put("samples", samples).put("releaseRemovedBlur", true).put("completed", true)
                    .put("depthStationaryFadeCropPriorityRollback", true)
                    .put("regionalWallpaperBlurLatestIntentAndRootScope", true)
                    .put("uid", android.os.Process.myUid()).put("fingerprint", Build.FINGERPRINT)
                    .put("windowAdded", false).put("realRenderThreadVerified", false).put("pixelsVerified", false);
        } catch (Throwable error) {
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
            try { report.put("completed", false).put("error", error.toString()); } catch (Exception ignored) {}
        } finally { System.out.println("FLYME_NATIVE_BLUR_PROBE " + report); System.exit(0); }
    }
}
