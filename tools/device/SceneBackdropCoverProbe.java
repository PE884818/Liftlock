package com.zhuanz.flymemotion.probe;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production fallback on owned off-display layers. No capture, task or input access. */
public final class SceneBackdropCoverProbe {
    private static Object call(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(object, values);
    }
    private static SurfaceControl node(String name, SurfaceControl parent) throws Exception {
        var builder = new SurfaceControl.Builder().setName("FlymeOwnedBackdrop-" + name).setParent(parent).setHidden(true);
        call(builder, "setContainerLayer", new Class<?>[0]); return builder.build();
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static void waitFor(Object cover, String method) throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + 4000;
        while (!(boolean)call(cover, method, new Class<?>[0])) {
            require(android.os.SystemClock.uptimeMillis() < end, "Timeout: " + method); Thread.sleep(10);
        }
    }
    public static void main(String[] args) throws Exception {
        require("myron".equals(Build.DEVICE) && Build.FINGERPRINT.contains("OS4.0.0.30.XPMCNXM"), "Wrong physical target");
        HandlerThread worker = new HandlerThread("FlymeOwnedBackdrop-probe"); worker.start();
        Handler handler = new Handler(worker.getLooper());
        SurfaceControl root = node("OffDisplay", null), lock = node("Lock", root), home = node("Home", root), home2 = node("HomeReplacement", root);
        Object cover = null;
        try {
            try (var t = new SurfaceControl.Transaction()) {
                call(t, "setLayerStack", new Class<?>[]{SurfaceControl.class, int.class}, root, 100821);
                t.setVisibility(root, true).setVisibility(lock, true).setVisibility(home, true).setVisibility(home2, true).apply();
            }
            Class<?> type = Class.forName("com.zhuanz.flymemotion.hook.ServerSceneCover");
            var constructor = type.getDeclaredConstructor(Handler.class); constructor.setAccessible(true); cover = constructor.newInstance(handler);
            AtomicInteger callbacks = new AtomicInteger();
            call(cover, "onChanged", new Class<?>[]{Runnable.class}, (Runnable)callbacks::incrementAndGet);
            CountDownLatch bound = new CountDownLatch(1);
            call(cover, "bind", new Class<?>[]{SurfaceControl.class, SurfaceControl.class, int.class, int.class, int.class, Runnable.class}, lock, root, 10, 64, 64, (Runnable)bound::countDown);
            require(bound.await(4, TimeUnit.SECONDS), "Cover bind not committed");
            call(cover, "prepareHome", new Class<?>[]{SurfaceControl.class, long.class}, home, 1L);
            waitFor(cover, "backdropReady");
            call(cover, "reveal", new Class<?>[]{boolean.class}, false);
            require(!(boolean)call(cover, "exposureComplete", new Class<?>[0]), "Wallpaper declared app ready");
            call(cover, "reveal", new Class<?>[]{boolean.class}, true);
            // Replacement during the fade must retain its current alpha and final commit.
            call(cover, "prepareHome", new Class<?>[]{SurfaceControl.class, long.class}, home2, 2L);
            waitFor(cover, "exposureComplete");
            require((long)call(cover, "backdropGeneration", new Class<?>[0]) == 2, "Replacement generation lost");
            call(cover, "protect", new Class<?>[0]);
            require(!(boolean)call(cover, "exposureComplete", new Class<?>[0]), "Protect did not cancel fade");
            call(cover, "reveal", new Class<?>[]{boolean.class}, true);
            call(cover, "protect", new Class<?>[0]);
            CountDownLatch drained = new CountDownLatch(1); handler.postDelayed(drained::countDown, 250);
            require(drained.await(4, TimeUnit.SECONDS), "Handler stalled");
            require(!(boolean)call(cover, "exposureComplete", new Class<?>[0]), "Stale fade removed cover");
            call(cover, "reveal", new Class<?>[]{boolean.class}, true); waitFor(cover, "exposureComplete");
            call(cover, "useNativeHome", new Class<?>[]{SurfaceControl.class,long.class}, home, 3L);
            waitFor(cover, "backdropReady");
            var mirrorField=type.getDeclaredField("home");mirrorField.setAccessible(true);
            require(mirrorField.get(cover)==null,"HOME has an opaque foreground clone");
            require((long)call(cover,"backdropLayer",new Class<?>[0])==(int)call(home,"getLayerId",new Class<?>[0]),"HOME is not the native wallpaper");
            call(cover,"protect",new Class<?>[0]);
            require(mirrorField.get(cover)==null,"Protect re-created a HOME foreground clone");
            call(cover,"requireLockProtection",new Class<?>[0]);
            require(!(boolean)call(cover,"backdropReady",new Class<?>[0]),"APP inherited HOME bypass");
            call(cover,"useNativeHome",new Class<?>[]{SurfaceControl.class,long.class},home,4L);waitFor(cover,"backdropReady");
            CountDownLatch retired = new CountDownLatch(1);
            try (var t = new SurfaceControl.Transaction()) {
                call(cover, "retire", new Class<?>[]{SurfaceControl.Transaction.class, Runnable.class}, t, (Runnable)retired::countDown); t.apply();
            }
            require(retired.await(4, TimeUnit.SECONDS), "Retirement not committed");
            require(!(boolean)call(cover, "bound", new Class<?>[0]) && !(boolean)call(cover, "backdropReady", new Class<?>[0]), "Retirement retained binding");
            require(home.isValid(),"Retirement released the unowned native HOME handle");
            System.out.println("{\"result\":\"passed\",\"productionFallback\":true,\"replacementDuringFade\":true,\"lateCommitRejected\":true,\"callbacks\":" + callbacks.get() + ",\"capturedPixels\":false,\"actualWallpaperOrAppTested\":false}");
        } finally {
            worker.quitSafely();
            try (var t = new SurfaceControl.Transaction()) { for (var s : new SurfaceControl[]{lock, home, home2, root}) t.reparent(s, null); t.apply(); }
            for (var s : new SurfaceControl[]{lock, home, home2, root}) s.release();
        }
        System.exit(0);
    }
}
