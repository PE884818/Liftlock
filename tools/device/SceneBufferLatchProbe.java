package com.zhuanz.flymemotion.probe;

import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.json.JSONObject;

/** Submit an owned buffer to an off-display layer. Never reads or captures pixels. */
public final class SceneBufferLatchProbe {
    public static void main(String[] args) throws Exception {
        int sdk=Build.VERSION.class.getField("SDK_INT").getInt(null);
        if (sdk!=37 || !"myron".equals(Build.DEVICE)) throw new IllegalStateException("Target required");
        var ctor=Class.forName("com.zhuanz.flymemotion.hook.NativePresentationFence").getDeclaredConstructor(ClassLoader.class);
        ctor.setAccessible(true); Object helper=ctor.newInstance(SurfaceControl.class.getClassLoader());
        Method listen=helper.getClass().getDeclaredMethod("listen",SurfaceControl.Transaction.class,BiConsumer.class); listen.setAccessible(true);
        var builder=new SurfaceControl.Builder().setName("FlymeMotion-Owned-Buffer-Latch").setHidden(true).setBufferSize(16,16);
        SurfaceControl owned=builder.build();
        try (HardwareBuffer buffer=HardwareBuffer.create(16,16,HardwareBuffer.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            CountDownLatch done=new CountDownLatch(1),commit=new CountDownLatch(1);
            AtomicBoolean latch=new AtomicBoolean(),fence=new AtomicBoolean();
            try(var t=new SurfaceControl.Transaction()) {
                Method stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,owned,100817);
                Method set=t.getClass().getDeclaredMethod("setBuffer",SurfaceControl.class,HardwareBuffer.class);set.setAccessible(true);set.invoke(t,owned,buffer);
                t.setVisibility(owned,true);
                listen.invoke(helper,t,(BiConsumer<Boolean,Boolean>)(l,f)->{latch.set(l);fence.set(f);done.countDown();});
                t.addTransactionCommittedListener(Runnable::run,commit::countDown);t.apply();
            }
            boolean committed=commit.await(4,TimeUnit.SECONDS),completed=done.await(4,TimeUnit.SECONDS);
            Class<?> statsClass=Class.forName("android.view.WindowContentFrameStats");
            Object stats=statsClass.getDeclaredConstructor().newInstance();
            Method readStats=SurfaceControl.class.getDeclaredMethod("getContentFrameStats",statsClass);readStats.setAccessible(true);
            boolean statsSupported=(boolean)readStats.invoke(owned,stats);
            int frames=(int)statsClass.getMethod("getFrameCount").invoke(stats);
            System.out.println(new JSONObject().put("probe","owned-buffer-off-display-no-pixel-read")
                    .put("committed",committed).put("completed",completed).put("latched",latch.get())
                    .put("presentFenceSignalled",fence.get()).put("statsSupported",statsSupported).put("statsFrames",frames)
                    .put("actualWindowTested",false).put("capturedPixels",false));
            if(!committed || !completed)throw new IllegalStateException("Receipt missing");
        } finally {
            try(var t=new SurfaceControl.Transaction()){t.reparent(owned,null).apply();}finally{owned.release();}
        }
        System.exit(0);
    }
}
