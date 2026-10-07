package com.zhuanz.flymemotion.probe;

import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.SurfaceControl;
import android.view.SurfaceControl$OnSurfaceStatsListener;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/** Target callback on one owned, off-display buffer layer. No screen or pixel read. */
public final class SceneSurfaceFrameProbe {
    public static void main(String[] args) throws Exception {
        if (Build.VERSION.class.getField("SDK_INT").getInt(null)!=37 || !"myron".equals(Build.DEVICE))
            throw new IllegalStateException("Target required");
        CountDownLatch callback=new CountDownLatch(1);
        AtomicLong first=new AtomicLong(-1),second=new AtomicLong(-1);
        var frames=new CopyOnWriteArrayList<String>();
        var listener=new SurfaceControl$OnSurfaceStatsListener() {
            @Override public void onNewFrame(long a,long b) {
                first.set(a);second.set(b);frames.add(a+":"+b);callback.countDown();
            }
        };
        Method add=SurfaceControl.class.getDeclaredMethod("addSurfaceStatsListener",listener.getClass().getSuperclass());
        Method remove=SurfaceControl.class.getDeclaredMethod("removeSurfaceStatsListener",listener.getClass().getSuperclass());
        add.setAccessible(true);remove.setAccessible(true);
        SurfaceControl owned=new SurfaceControl.Builder().setName("FlymeMotion-Owned-Surface-Frame").setHidden(true).setBufferSize(16,16).build();
        try (HardwareBuffer buffer=HardwareBuffer.create(16,16,HardwareBuffer.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            add.invoke(owned,listener);
            Thread.sleep(200);
            String before=frames.toString();frames.clear();
            try(var t=new SurfaceControl.Transaction()) {
                Method stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,owned,100819);
                Method set=t.getClass().getDeclaredMethod("setBuffer",SurfaceControl.class,HardwareBuffer.class);set.setAccessible(true);set.invoke(t,owned,buffer);
                t.setVisibility(owned,true).apply();
            }
            boolean received=callback.await(2,TimeUnit.SECONDS);
            Thread.sleep(200);
            String after=frames.toString();frames.clear();
            try(var t=new SurfaceControl.Transaction()){t.setPosition(owned,1,1).apply();}
            Thread.sleep(200);
            System.out.println(new JSONObject().put("probe","owned-Surface-frame-off-display-no-pixel-read")
                .put("received",received).put("layer",SurfaceControl.class.getDeclaredMethod("getLayerId").invoke(owned))
                .put("before",before).put("buffer",after).put("position",frames.toString())
                .put("first",first.get()).put("second",second.get()).put("capturedPixels",false));
        } finally {
            remove.invoke(owned,listener);
            try(var t=new SurfaceControl.Transaction()){t.reparent(owned,null).apply();}finally{owned.release();}
        }
        System.exit(0);
    }
}
