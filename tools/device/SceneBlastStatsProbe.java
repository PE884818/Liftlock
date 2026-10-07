package com.zhuanz.flymemotion.probe;
import android.graphics.Canvas;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl$OnSurfaceStatsListener;
import java.util.concurrent.CopyOnWriteArrayList;
import android.os.Build;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Owned off-display BLAST producer; renders solid test pixels, never reads any pixels. */
public final class SceneBlastStatsProbe {
    public static void main(String[] args)throws Exception{
        if(Build.VERSION.class.getField("SDK_INT").getInt(null)!=37 || !"myron".equals(Build.DEVICE))throw new IllegalStateException("Target required");
        var builder=new SurfaceControl.Builder().setName("FlymeMotion-Owned-BLAST-Stats").setHidden(true);
        Method blast=builder.getClass().getDeclaredMethod("setBLASTLayer");blast.setAccessible(true);blast.invoke(builder);
        SurfaceControl layer=builder.build();Object queue=null;Surface producer=null;
        var frames=new CopyOnWriteArrayList<String>();
        var listener=new SurfaceControl$OnSurfaceStatsListener(){public void onNewFrame(long a,long b){frames.add(a+":"+b);}};
        Method add=SurfaceControl.class.getDeclaredMethod("addSurfaceStatsListener",listener.getClass().getSuperclass());add.setAccessible(true);add.invoke(layer,listener);
        try{
            try(var t=new SurfaceControl.Transaction()){
                Method stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,layer,100818);
                t.setVisibility(layer,true).apply();
            }
            Class<?> q=Class.forName("android.graphics.BLASTBufferQueue");
            var ctor=q.getDeclaredConstructor(String.class,SurfaceControl.class,int.class,int.class,int.class,float.class);ctor.setAccessible(true);
            queue=ctor.newInstance("FlymeMotion-Owned-Stats",layer,16,16,1,1f);
            Method create=q.getDeclaredMethod("createSurface");create.setAccessible(true);producer=(Surface)create.invoke(queue);
            Class<?> statsClass=Class.forName("android.view.WindowContentFrameStats");Object stats=statsClass.getDeclaredConstructor().newInstance();
            Method read=SurfaceControl.class.getDeclaredMethod("getContentFrameStats",statsClass);read.setAccessible(true);
            CountDownLatch done=new CountDownLatch(1);
            Method next=q.getDeclaredMethod("syncNextTransaction",Consumer.class);next.setAccessible(true);
            next.invoke(queue,(Consumer<SurfaceControl.Transaction>)t->{t.addTransactionCommittedListener(Runnable::run,done::countDown);t.apply();});
            Canvas c=producer.lockCanvas(null);c.drawColor(0xff123456);producer.unlockCanvasAndPost(c);
            boolean committed=done.await(4,TimeUnit.SECONDS);
            Thread.sleep(100);String firstFrames=frames.toString();frames.clear();
            Canvas second=producer.lockCanvas(null);second.drawColor(0xff654321);producer.unlockCanvasAndPost(second);
            Thread.sleep(100);
            boolean supported=(boolean)read.invoke(layer,stats);int count=(int)statsClass.getMethod("getFrameCount").invoke(stats);
            JSONObject result=new JSONObject().put("probe","owned-BLAST-off-display-no-pixel-read").put("bufferCommitted",committed).put("statsSupported",supported).put("frameCount",count).put("firstFrames",firstFrames).put("secondFrames",frames.toString());
            if(count>0){int last=count-1;result.put("posted",statsClass.getMethod("getFramePostedTimeNano",int.class).invoke(stats,last))
                .put("ready",statsClass.getMethod("getFrameReadyTimeNano",int.class).invoke(stats,last))
                .put("presented",statsClass.getMethod("getFramePresentedTimeNano",int.class).invoke(stats,last));}
            System.out.println(result);if(!committed)throw new IllegalStateException("Buffer commit missing");
        }finally{
            Method remove=SurfaceControl.class.getDeclaredMethod("removeSurfaceStatsListener",listener.getClass().getSuperclass());remove.setAccessible(true);remove.invoke(layer,listener);
            if(producer!=null)producer.release();if(queue!=null){Method destroy=queue.getClass().getDeclaredMethod("destroy");destroy.setAccessible(true);destroy.invoke(queue);}
            try(var t=new SurfaceControl.Transaction()){t.reparent(layer,null).apply();}finally{layer.release();}
        }System.exit(0);
    }
}
