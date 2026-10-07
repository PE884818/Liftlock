package com.zhuanz.flymemotion.hook;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import com.zhuanz.flymemotion.core.HomeUnlockGate;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

/** The Flutter HOME uses WMS's locked-state listener, not the legacy launcher proxy. */
final class LateHomeLockedStateHooks {
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HomeUnlockGate gate=new HomeUnlockGate();
    private final Map<IBinder,Integer> listeners=new HashMap<>();
    private final Map<IBinder,Object> pending=new HashMap<>();
    private final ThreadLocal<Boolean> forwarding=new ThreadLocal<>();
    private final ArrayList<XposedInterface.HookHandle> handles=new ArrayList<>();
    private Object wm;
    private Method callback, locked;
    private boolean supported;
    private long deadlineSession=-1;
    private final Runnable timeout=()->finish(deadlineSession);
    LateHomeLockedStateHooks(MotionModule m,ClassLoader l,RuntimeReporter r){module=m;loader=l;reporter=r;}
    void install(){
        try{
            Class<?> owner=Class.forName("com.android.server.wm.WindowManagerService",false,loader);
            Class<?> listener=Class.forName("com.android.internal.policy.IKeyguardLockedStateListener",false,loader);
            Class<?> proxy=Class.forName("com.android.internal.policy.IKeyguardLockedStateListener$Stub$Proxy",false,loader);
            locked=NativeReflection.method(owner,"isKeyguardLocked");
            callback=NativeReflection.method(proxy,"onKeyguardLockedStateChanged",boolean.class);
            hook(NativeReflection.method(owner,"addKeyguardLockedStateListener",listener),chain->{
                int uid=Binder.getCallingUid();Object value=chain.getArg(0);Object result=chain.proceed();
                if(value instanceof IInterface i){synchronized(this){wm=chain.getThisObject();listeners.put(i.asBinder(),uid);}}return result;});
            hook(NativeReflection.method(owner,"removeKeyguardLockedStateListener",listener),chain->{
                Object value=chain.getArg(0);if(value instanceof IInterface i){synchronized(this){listeners.remove(i.asBinder());pending.remove(i.asBinder());}}return chain.proceed();});
            hook(callback,chain->{
                if(Boolean.TRUE.equals(forwarding.get())||!(chain.getThisObject() instanceof IInterface i))return chain.proceed();
                boolean isLocked=(boolean)chain.getArg(0);boolean held=false;
                synchronized(this){
                    Integer uid=listeners.get(i.asBinder());
                    if(isLocked)pending.remove(i.asBinder());
                    else if(uid!=null&&gate.hold(uid,false)&&i.asBinder().isBinderAlive()){
                        pending.put(i.asBinder(),chain.getThisObject());held=true;
                        if(deadlineSession!=gate.session()){deadlineSession=gate.session();handler.removeCallbacks(timeout);handler.postDelayed(timeout,3000);}
                    }
                }
                if(!held)return chain.proceed();
                state("waiting-handoff","HOME native unlocked notification retained; session="+gate.session());return null;
            });
            // The dispatch lambda's index ($7 on the inspected build) shifts between releases.
            module.deoptimize(NativeReflection.method(owner,"dispatchKeyguardLockedState"));
            for(Method m:owner.getDeclaredMethods())if(m.getName().startsWith("lambda$dispatchKeyguardLockedState$"))module.deoptimize(m);
            supported=true;state("framework-ready","HOME listener adapter ready; other UIDs/authentication unchanged");
        }catch(Throwable t){for(var h:handles)h.unhook();handles.clear();state("unsupported",t.getClass().getSimpleName()+": "+t.getMessage());}
    }
    boolean supported(){return supported;}
    void arm(long id,int homeUid,boolean selected){synchronized(this){
        if(gate.session()!=id){pending.clear();handler.removeCallbacks(timeout);deadlineSession=-1;}
        gate.arm(id,homeUid,selected&&supported);
    }}
    void finish(long id){
        Map<IBinder,Object> delivery;
        synchronized(this){if(!gate.finish(id))return;delivery=new HashMap<>(pending);pending.clear();handler.removeCallbacks(timeout);deadlineSession=-1;}
        // A binder delivery never runs while the WM global lock is held.
        handler.post(()->{
            boolean nowLocked=true;
            try{nowLocked=wm==null||!Boolean.FALSE.equals(NativeReflection.invoke(locked,wm));}
            catch(Throwable t){state("failed","native keyguard state unavailable; deferred false callback discarded");}
            for(var item:delivery.entrySet()){
                synchronized(this){if(gate.session()!=id||!listeners.containsKey(item.getKey()))continue;}
                if(nowLocked||!item.getKey().isBinderAlive())continue;
                forwarding.set(true);
                try{NativeReflection.invoke(callback,item.getValue(),false);state("played","HOME native notification delivered after scene restore; session="+id);}
                catch(Throwable t){state("failed","HOME callback: "+t.getClass().getSimpleName());}
                finally{forwarding.remove();}
            }
        });
    }
    private void state(String s,String d){reporter.desktopAnimationState=s;reporter.desktopAnimationDetail=d;reporter.publish();}
    private void hook(Method m,XposedInterface.Hooker h){handles.add(module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(h));}
}
