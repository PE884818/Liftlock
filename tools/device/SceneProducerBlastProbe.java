package com.zhuanz.flymemotion.probe;

import android.graphics.Canvas;
import android.view.Surface;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.hook.NativeSurfaceFrame;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Actual owned BLAST frame numbers; no display, window, user pixels or screenshots. */
public final class SceneProducerBlastProbe {
    public static void main(String[] args){try{run();}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}System.exit(0);}
    private static void run()throws Exception {
        NativeSurfaceFrame.load("/data/local/tmp/libflymesurfaceframe.so");
        var builder=new SurfaceControl.Builder().setName("FlymeMotion-Owned-Producer-BLAST").setHidden(true);
        var blast=builder.getClass().getDeclaredMethod("setBLASTLayer");blast.setAccessible(true);blast.invoke(builder);
        SurfaceControl layer=builder.build();Surface producer=null;Object queue=null;
        var frames=new CopyOnWriteArrayList<Long>();CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
        try(var observer=new NativeSurfaceFrame(layer,n->{frames.add(n);if(n==1)first.countDown();if(n==2)second.countDown();})) {
            try(var t=new SurfaceControl.Transaction()){
                var stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,layer,100822);t.setVisibility(layer,true).apply();
            }
            Class<?> q=Class.forName("android.graphics.BLASTBufferQueue");
            var ctor=q.getDeclaredConstructor(String.class,SurfaceControl.class,int.class,int.class,int.class,float.class);ctor.setAccessible(true);
            queue=ctor.newInstance("FlymeMotion-Owned-Producer",layer,16,16,1,1f);
            var create=q.getDeclaredMethod("createSurface");create.setAccessible(true);producer=(Surface)create.invoke(queue);
            Canvas c=producer.lockCanvas(null);c.drawColor(0xff123456);producer.unlockCanvasAndPost(c);
            if(!first.await(3,TimeUnit.SECONDS))throw new IllegalStateException("First BLAST fence missing");
            frames.clear();try(var t=new SurfaceControl.Transaction()){t.setPosition(layer,1,1).apply();}
            Thread.sleep(200);boolean metadataAccepted=!frames.isEmpty();
            c=producer.lockCanvas(null);c.drawColor(0xff654321);producer.unlockCanvasAndPost(c);
            if(!second.await(3,TimeUnit.SECONDS)||metadataAccepted)throw new IllegalStateException("BLAST proof invalid");
            System.out.println(new JSONObject().put("probe","production-BLAST-fence-owned-off-display")
                .put("firstFrame",1).put("secondFrames",frames.toString()).put("metadataAccepted",metadataAccepted).put("capturedPixels",false));
        }finally{
            if(producer!=null)producer.release();if(queue!=null){var destroy=queue.getClass().getDeclaredMethod("destroy");destroy.setAccessible(true);destroy.invoke(queue);}
            try(var t=new SurfaceControl.Transaction()){t.reparent(layer,null).apply();}finally{layer.release();}
        }
    }
}
