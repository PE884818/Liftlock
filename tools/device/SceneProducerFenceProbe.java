package com.zhuanz.flymemotion.probe;

import android.hardware.HardwareBuffer;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.hook.NativeSurfaceFrame;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Executes the production bridge on an owned off-display producer; no pixel read. */
public final class SceneProducerFenceProbe {
    public static void main(String[] args) {
        try { run(args); } catch(Throwable failure){ failure.printStackTrace(System.out);System.exit(1); }
    }
    private static void run(String[] args)throws Exception {
        NativeSurfaceFrame.load(args.length==0?"/data/local/tmp/libflymesurfaceframe.so":args[0]);
        SurfaceControl owned=new SurfaceControl.Builder().setName("FlymeMotion-Owned-Producer-Fence").setHidden(true).setBufferSize(16,16).build();
        CountDownLatch done=new CountDownLatch(1);AtomicLong frame=new AtomicLong(-1);
        try(var observer=new NativeSurfaceFrame(owned,n->{frame.set(n);done.countDown();});
            HardwareBuffer buffer=HardwareBuffer.create(16,16,HardwareBuffer.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            try(var t=new SurfaceControl.Transaction()) {
                Method stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,owned,100820);
                t.setVisibility(owned,true).apply();
            }
            boolean emptyAccepted=done.await(200,TimeUnit.MILLISECONDS);
            long submitted=System.nanoTime();
            try(var t=new SurfaceControl.Transaction()) {
                Method set=t.getClass().getDeclaredMethod("setBuffer",SurfaceControl.class,HardwareBuffer.class);set.setAccessible(true);set.invoke(t,owned,buffer);t.apply();
            }
            boolean realAccepted=done.await(3,TimeUnit.SECONDS);
            System.out.println(new JSONObject().put("probe","production-producer-fence-owned-off-display")
                .put("emptyAccepted",emptyAccepted).put("realAccepted",realAccepted).put("frame",frame.get())
                .put("elapsedMs",(System.nanoTime()-submitted)/1000000).put("capturedPixels",false));
            if(emptyAccepted||!realAccepted||frame.get()!=100)throw new IllegalStateException("Producer proof invalid");
        }finally{
            try(var t=new SurfaceControl.Transaction()){t.reparent(owned,null).apply();}finally{owned.release();}
        }
        System.exit(0);
    }
}
