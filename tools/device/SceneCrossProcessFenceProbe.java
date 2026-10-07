package com.zhuanz.flymemotion.probe;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.view.SurfaceControl;
import android.hardware.HardwareBuffer;
import com.zhuanz.flymemotion.hook.NativeSurfaceFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** One owned producer shared over Binder, two processes, no display or pixel read. */
public final class SceneCrossProcessFenceProbe {
    public static void main(String[] args) {
        try {if(args.length>0)produce(args[0].equals("client"));else observe();}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}
        System.exit(0);
    }
    private static final String SERVICE="flyme_owned_surface_probe";
    private static void observe()throws Exception {
        NativeSurfaceFrame.load("/data/local/tmp/libflymesurfaceframe.so");
        SurfaceControl layer=new SurfaceControl.Builder().setName("FlymeMotion-Owned-Cross-Process").setHidden(true).setBufferSize(16,16).build();
        CountDownLatch done=new CountDownLatch(1);AtomicLong frame=new AtomicLong(-1);
        try(var observer=new NativeSurfaceFrame(layer,n->{frame.set(n);done.countDown();})) {
            try(var t=new SurfaceControl.Transaction()){
                var stack=t.getClass().getDeclaredMethod("setLayerStack",SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,layer,100821);t.setVisibility(layer,true).apply();
            }
            Binder service=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                if(Binder.getCallingUid()!=0)return false;
                if(code==1){reply.writeNoException();layer.writeToParcel(reply,0);return true;}
                if(code==2){long acknowledged=data.readLong();if(acknowledged==100){frame.set(acknowledged);done.countDown();}reply.writeNoException();return true;}return false;
            }};
            Class<?> manager=Class.forName("android.os.ServiceManager");
            var add=manager.getDeclaredMethod("addService",String.class,IBinder.class);add.setAccessible(true);add.invoke(null,SERVICE,service);
            System.out.println("OWNED_SERVICE_READY");System.out.flush();
            boolean accepted=done.await(8,TimeUnit.SECONDS);
            System.out.println(new JSONObject().put("probe","cross-process-owned-producer-fence")
                .put("accepted",accepted).put("frame",frame.get()).put("capturedPixels",false));
        }finally{try(var t=new SurfaceControl.Transaction()){t.reparent(layer,null).apply();}finally{layer.release();}}
    }
    private static void produce(boolean clientObserver)throws Exception {
        Class<?> manager=Class.forName("android.os.ServiceManager");var get=manager.getDeclaredMethod("getService",String.class);get.setAccessible(true);
        IBinder binder=(IBinder)get.invoke(null,SERVICE);if(binder==null)throw new IllegalStateException("Owned service missing");
        SurfaceControl layer;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{if(!binder.transact(1,data,reply,0))throw new IllegalStateException("Owned surface unavailable");reply.readException();layer=SurfaceControl.CREATOR.createFromParcel(reply);}
        finally{data.recycle();reply.recycle();}
        CountDownLatch clientDone=new CountDownLatch(1);
        NativeSurfaceFrame client=null;
        if(clientObserver){
            NativeSurfaceFrame.load("/data/local/tmp/libflymesurfaceframe.so");
            client=new NativeSurfaceFrame(layer,frame->{
                Parcel d=Parcel.obtain(),r=Parcel.obtain();
                try{d.writeLong(frame);if(!binder.transact(2,d,r,0))throw new IllegalStateException("Receipt rejected");r.readException();clientDone.countDown();}
                catch(Exception e){e.printStackTrace(System.out);}finally{d.recycle();r.recycle();}
            });
        }
        try(HardwareBuffer buffer=HardwareBuffer.create(16,16,HardwareBuffer.RGBA_8888,1,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)){
            try(var t=new SurfaceControl.Transaction()){
                var set=t.getClass().getDeclaredMethod("setBuffer",SurfaceControl.class,HardwareBuffer.class);set.setAccessible(true);set.invoke(t,layer,buffer);t.apply();
            }
            System.out.println("OWNED_FOREIGN_BUFFER_SUBMITTED");
            if(clientObserver&&!clientDone.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Client producer receipt missing");
            Thread.sleep(200);
        }finally{if(client!=null)client.close();layer.release();}
    }
}
