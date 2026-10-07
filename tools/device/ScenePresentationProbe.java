package com.zhuanz.flymemotion.probe;

import android.os.Build;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.json.JSONObject;

/** Target-only ABI validation on a new owned off-display layer. No windows/pixels/capture. */
public final class ScenePresentationProbe {
    private static Object call(Object owner, String name, Class<?>[] args, Object... values) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, args); method.setAccessible(true);
        return method.invoke(owner, values);
    }
    public static void main(String[] args) throws Exception {
        // Use reflection: android.jar final SDK_INT may otherwise be folded by javac.
        int sdk = Build.VERSION.class.getField("SDK_INT").getInt(null);
        if (sdk != 37 || !"myron".equals(Build.DEVICE) || !Build.FINGERPRINT.contains("OS4.0.0.30.XPMCNXM"))
            throw new IllegalStateException("Target-only presentation probe: " + Build.FINGERPRINT + " sdk=" + sdk);
        SurfaceControl owned = null;
        try {
            Class<?> helperType = Class.forName("com.zhuanz.flymemotion.hook.NativePresentationFence");
            var constructor = helperType.getDeclaredConstructor(ClassLoader.class); constructor.setAccessible(true);
            Object helper = constructor.newInstance(SurfaceControl.class.getClassLoader());
            var builder = new SurfaceControl.Builder().setName("FlymeMotion-Owned-OffDisplay-Receipt").setHidden(true);
            call(builder, "setContainerLayer", new Class<?>[0]); owned = builder.build();
            CountDownLatch completed = new CountDownLatch(1), committed = new CountDownLatch(1);
            AtomicBoolean latched = new AtomicBoolean(), signalled = new AtomicBoolean();
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                call(transaction, "setLayerStack", new Class<?>[]{SurfaceControl.class,int.class}, owned, 100814);
                transaction.setVisibility(owned, true);
                call(helper, "listen", new Class<?>[]{SurfaceControl.Transaction.class,BiConsumer.class}, transaction,
                        (BiConsumer<Boolean,Boolean>)(latch,fence) -> { latched.set(latch); signalled.set(fence); completed.countDown(); });
                transaction.addTransactionCommittedListener(Runnable::run, committed::countDown); transaction.apply();
            }
            boolean submitted = committed.await(4,TimeUnit.SECONDS), callback = completed.await(4,TimeUnit.SECONDS);
            JSONObject result = new JSONObject().put("probe", "owned-off-display-no-pixels")
                    .put("sdk",sdk).put("fingerprint",Build.FINGERPRINT).put("committed",submitted)
                    .put("completionCallback",callback).put("latched",latched.get()).put("presentFenceSignalled",signalled.get())
                    .put("frameDeclaredReady",false).put("actualWindowTested",false).put("capturedPixels",false)
                    .put("result",submitted && callback ? "passed" : "failed");
            System.out.println(result);
            if (!submitted || !callback) throw new IllegalStateException("Target completion ABI did not deliver bounded callbacks");
        } finally {
            if (owned != null) {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { t.reparent(owned,null).apply(); }
                finally { owned.release(); }
            }
        }
        // app_process owns this worker; no resident test service is left running.
        System.exit(0);
    }
}
