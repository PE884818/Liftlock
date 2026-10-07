package com.zhuanz.flymemotion.hook;

import android.app.Application;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import com.zhuanz.flymemotion.SceneProtocol;
import com.zhuanz.flymemotion.TargetBuild;
import com.zhuanz.flymemotion.core.SceneRequestOrder;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.util.List;

/** No animation or app-content hook. Observe only the framework-selected return producer. */
final class ClientFrameHooks {
    private final MotionModule module;
    private final Handler main=new Handler(Looper.getMainLooper());
    private NativeSurfaceFrame observer;
    private long session=-1,binding=-1;
    private Context context;
    private final SceneRequestOrder order=new SceneRequestOrder();
    private Intent pending;
    private boolean initialized;
    private Throwable initializationFailure;
    ClientFrameHooks(MotionModule module){this.module=module;}
    void install(XposedModuleInterface.PackageReadyParam param)throws Exception {
        String appName=param.getApplicationInfo().className;
        Class<?> app=appName==null?Application.class:Class.forName(appName,false,param.getClassLoader());
        module.hook(NativeReflection.method(app,"onCreate")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object result=chain.proceed();attach((Context)chain.getThisObject());return result;
        });
    }
    private void attach(Context app) {
        if(context!=null)return;context=app;
        context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(getSentFromUid()!=1000 || !"android".equals(getSentFromPackage()))return;
            if(!TargetBuild.sameProtocol(intent.getStringExtra("version")))return;
            if("end".equals(intent.getStringExtra("command"))){
                if(order.end(intent.getLongExtra("token",-1),intent.getLongExtra("binding",-1))){pending=null;close();}return;
            }
            if(!"begin".equals(intent.getStringExtra("command")))return;
            if(!order.begin(intent.getLongExtra("token",-1),intent.getLongExtra("binding",-1)))return;
            close();pending=new Intent(intent);drain();
        }},new IntentFilter(SceneProtocol.CLIENT_FRAME),null,main,Context.RECEIVER_EXPORTED);
        // Exactly one initialization attempt per process, before any lockscreen gesture.
        // File hashing and dynamic linking never run in the UI/Binder/WM hot path.
        new Thread(()->{
            Throwable failure=null;
            try{NativeSurfaceFrame.loadPackaged();}catch(Throwable problem){failure=problem;}
            Throwable result=failure;
            main.post(()->{
                initialized=true;initializationFailure=result;
                if(result==null)module.log(android.util.Log.INFO,"FlymeMotion","Producer JNI READY "+TargetBuild.MODULE_VERSION+" in "+context.getPackageName());
                else module.log(android.util.Log.ERROR,"FlymeMotion","Producer JNI FAILED "+TargetBuild.MODULE_VERSION,result);
                drain();
            });
        },"FlymeMotion-producer-init").start();
    }
    private void drain(){
        if(!initialized||pending==null)return;
        Intent request=pending;pending=null;
        if(!order.current(request.getLongExtra("token",-1),request.getLongExtra("binding",-1)))return;
        if(initializationFailure!=null){reportFailure(request,initializationFailure);return;}
        try{begin(request);}catch(Throwable failure){close();reportFailure(request,failure);}
    }
    private void reportFailure(Intent request,Throwable failure){
        module.log(android.util.Log.ERROR,"FlymeMotion","Selected producer observer failed",failure);
        String message=String.valueOf(failure.getMessage()).replace('\n',' ').replace('\r',' ');
        if(message.length()>512)message=message.substring(0,512);
        reply(request,-1,"客户端缓冲观察失败："+failure.getClass().getSimpleName()+": "+message);
    }
    private void begin(Intent intent)throws Exception {
        Bundle extras=intent.getExtras();IBinder selected=extras==null?null:extras.getBinder("window");
        if(selected==null)return;
        Class<?> globalClass=Class.forName("android.view.WindowManagerGlobal");
        Object global=NativeReflection.invoke(NativeReflection.method(globalClass,"getInstance"),null);
        Object selectedRoot=null;
        synchronized(NativeReflection.get(global,"mLock")){
            for(Object root:(List<?>)NativeReflection.get(global,"mRoots")){
                Object client=NativeReflection.get(root,"mWindow");
                if(selected.equals(NativeReflection.call(client,"asBinder"))){selectedRoot=root;break;}
            }
        }
        // An explicit package broadcast can reach several processes. Only the owner of
        // this exact IWindow may acknowledge it; other roots/processes stay untouched.
        if(selectedRoot==null)return;
        SurfaceControl surface=(SurfaceControl)NativeReflection.get(selectedRoot,"mSurfaceControl");
        long layer=surface.isValid()?((Number)NativeReflection.call(surface,"getLayerId")).longValue():-1;
        if(layer!=intent.getLongExtra("layer",-1)){reply(intent,-1,"返回窗口 Surface 已替换");return;}
        close();session=intent.getLongExtra("token",-1);binding=intent.getLongExtra("binding",-1);
        long activeSession=session,activeBinding=binding;
        Object owner=selectedRoot;
        observer=new NativeSurfaceFrame(surface,frame->main.post(()->{
            if(!order.current(activeSession,activeBinding)||session!=activeSession||binding!=activeBinding)return;
            SurfaceControl current=(SurfaceControl)NativeReflection.get(owner,"mSurfaceControl");
            if(!current.isValid()||((Number)NativeReflection.call(current,"getLayerId")).longValue()!=layer){close();return;}
            reply(intent,frame,"");close();
        }));
        // Runs in the actual owning UI thread. A new full invalidation generates a real
        // producer buffer even when WMS finishDrawing carries only metadata.
        NativeReflection.invoke(NativeReflection.method(selectedRoot.getClass(),"setReportNextDraw",boolean.class,String.class),selectedRoot,true,"Flyme selected return frame");
        View view=(View)NativeReflection.get(selectedRoot,"mView");view.invalidate();
        main.postDelayed(()->{if(session==activeSession&&binding==activeBinding)close();},5000);
    }
    private void close(){session=binding=-1;if(observer!=null){observer.close();observer=null;}}
    private void reply(Intent request,long frame,String failure){
        Intent receipt=new Intent(SceneProtocol.CLIENT_FRAME_STATUS).setPackage("android")
            // Echo the authenticated request's wire version; 0.2.18 framework is compatible.
            .putExtra("version",request.getStringExtra("version")).putExtra("clientVersion",TargetBuild.MODULE_VERSION).putExtra("token",request.getLongExtra("token",-1))
            .putExtra("binding",request.getLongExtra("binding",-1)).putExtra("layer",request.getLongExtra("layer",-1))
            .putExtra("frame",frame).putExtra("failure",failure);
        Bundle data=new Bundle();data.putBinder("window",request.getExtras().getBinder("window"));receipt.putExtras(data);
        BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
        context.sendBroadcast(receipt,null,options.toBundle());
    }
}
