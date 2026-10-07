package com.zhuanz.flymemotion.probe;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the production mirror submit when Shade has no next ViewRoot draw. No pixels. */
public final class SceneHiddenRootProbe {
    public static void main(String[] args) throws Exception {
        if (!"myron".equals(Build.DEVICE) || !Build.FINGERPRINT.contains("OS4.0.0.30.XPMCNXM"))
            throw new IllegalStateException("Wrong physical target");
        Looper.prepareMainLooper();
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Method systemMain = threadType.getDeclaredMethod("systemMain"); systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method contextMethod = threadType.getDeclaredMethod("getSystemContext"); contextMethod.setAccessible(true);
        Context context = (Context) contextMethod.invoke(thread);
        View hidden = new View(context);
        if (hidden.isShown() || hidden.getWindowVisibility() == View.VISIBLE)
            throw new IllegalStateException("Probe must have no visible/drawing window");
        Class<?> reporterType = Class.forName("com.zhuanz.flymemotion.hook.RuntimeReporter");
        var rc = reporterType.getDeclaredConstructor(String.class); rc.setAccessible(true);
        Object reporter = rc.newInstance("owned-probe");
        Class<?> type = Class.forName("com.zhuanz.flymemotion.hook.NativeWallpaperComposition");
        var constructor = type.getDeclaredConstructor(ClassLoader.class, reporterType); constructor.setAccessible(true);
        Object composition = constructor.newInstance(SurfaceControl.class.getClassLoader(), reporter);
        Field view = type.getDeclaredField("view"); view.setAccessible(true); view.set(composition, hidden);
        // root stays null: any attempt to enqueue on a nonexistent ViewRoot must fail.
        Method submit = type.getDeclaredMethod("submit", SurfaceControl.Transaction.class); submit.setAccessible(true);
        var builder = new SurfaceControl.Builder().setName("FlymeMotion-Owned-HiddenRoot-Commit").setHidden(true);
        Method container = builder.getClass().getDeclaredMethod("setContainerLayer"); container.setAccessible(true); container.invoke(builder);
        SurfaceControl owned = builder.build();
        try {
            CountDownLatch committed = new CountDownLatch(1);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                Method stack = t.getClass().getDeclaredMethod("setLayerStack", SurfaceControl.class, int.class); stack.setAccessible(true);
                stack.invoke(t, owned, 100820);
                t.setVisibility(owned, true).setAlpha(owned, 0);
                t.addTransactionCommittedListener(Runnable::run, committed::countDown);
                submit.invoke(composition, t);
            }
            if (!committed.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Hidden root transaction stalled");
            System.out.println("{\"result\":\"passed\",\"productionSubmit\":true,\"rootDrawRequired\":false,\"committed\":true,\"capturedPixels\":false,\"actualUnlockTested\":false}");
        } finally {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { t.reparent(owned, null).apply(); }
            owned.release();
        }
        System.exit(0);
    }
}
