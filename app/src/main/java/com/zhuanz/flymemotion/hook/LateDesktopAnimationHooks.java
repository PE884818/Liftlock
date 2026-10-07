package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.IInterface;
import com.zhuanz.flymemotion.core.DesktopAnimationGate;
import com.zhuanz.flymemotion.core.MotionConfig;
import java.lang.reflect.Method;
import java.util.ArrayList;
import io.github.libxposed.api.XposedInterface;

/** Delay only the registered HOME animation IPC, leaving windows, drawing and auth untouched. */
final class LateDesktopAnimationHooks {
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final DesktopAnimationGate gate=new DesktopAnimationGate();
    private final ArrayList<XposedInterface.HookHandle> handles=new ArrayList<>();
    private Object controller,registered,pending;
    private Method prepare,play,reset;
    private Object[] prepareArgs;
    private long token=-1,playDelay;
    private boolean supported,writing,playQueued,handoffComplete;
    private final Runnable timeout=()->cancel("native animation did not reach completed scene handoff");
    LateDesktopAnimationHooks(MotionModule module,ClassLoader loader,RuntimeReporter reporter){this.module=module;this.loader=loader;this.reporter=reporter;}
    void install(){
        try{
            Class<?> owner=Class.forName("com.android.systemui.keyguard.KeyguardUnlockAnimationController",false,loader);
            Class<?> proxy=Class.forName("com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController$Stub$Proxy",false,loader);
            prepare=NativeReflection.method(proxy,"prepareForUnlock",Rect.class,boolean.class,int.class);
            play=NativeReflection.method(proxy,"playUnlockAnimation",long.class);reset=NativeReflection.method(proxy,"setUnlockAmount",boolean.class);
            Method bind=NativeReflection.method(owner,"setLauncherUnlockController",String.class,proxy);
            hook(bind,chain->{Object result=chain.proceed();cancel("launcher binding changed");controller=chain.getThisObject();
                registered=chain.getArg(1);
                state(registered==null?"waiting-launcher":"ready","native launcher animation binding; no HOME hooks");return result;});
            hook(prepare,chain->{if(!mayDefer(chain.getThisObject()))return chain.proceed();
                pending=chain.getThisObject();prepareArgs=new Object[]{new Rect((Rect)chain.getArg(0)),chain.getArg(1),chain.getArg(2)};
                gate.prepared(token);state("waiting-handoff","native prepare retained; gesture="+token);scheduleTimeout();return null;});
            hook(play,chain->{if(!mayDefer(chain.getThisObject())||pending!=chain.getThisObject()||prepareArgs==null)return chain.proceed();
                playQueued=true;playDelay=Math.max(0,(long)chain.getArg(0));gate.play(token);state("waiting-handoff","native play retained; gesture="+token);scheduleTimeout();if(handoffComplete)complete(token);return null;});
            if(!module.deoptimize(NativeReflection.method(owner,"onKeyguardGoingAwayChanged"))
                    ||!module.deoptimize(NativeReflection.method(owner,"unlockToLauncherWithInWindowAnimations")))throw new IllegalStateException("Native launcher callers not deoptimized");
            supported=true;reporter.hooks+=handles.size();state("ready","HOME resume withheld until native unlock; legacy launcher binding optional");
        }catch(Throwable t){for(var handle:handles)handle.unhook();handles.clear();supported=false;state("unsupported",t.getClass().getSimpleName()+": "+t.getMessage());}
    }
    boolean nativeBound(){return supported&&registered!=null;}
    void begin(long generation,MotionConfig config){cancel("new gesture");token=generation;handoffComplete=false;gate.begin(generation,config.enabled&&config.lateDesktopAnimation&&supported);
        // Without a legacy launcher binding, NativeSceneChannel reports the HOME resume path.
        if(supported&&registered!=null)state(!config.lateDesktopAnimation?"disabled":"armed","gesture="+generation+"; native preview remains enabled");}
    private boolean mayDefer(Object proxy){return !writing&&supported&&registered==proxy&&controller!=null&&(!handoffComplete||pending!=null)
        &&gate.accepts(token,"HOME".equals(reporter.returnTarget))
        &&Boolean.TRUE.equals(NativeReflection.call(controller,"isSupportedLauncherUnderneath"))
        &&proxy instanceof IInterface remote&&remote.asBinder().isBinderAlive();}
    void complete(long generation){
        if(generation!=token)return;handoffComplete=true;gate.released(generation);
        if(!gate.consume(generation))return;
        main.removeCallbacks(timeout);Object target=pending;Object[] args=prepareArgs;long delay=playDelay;boolean hadPlay=playQueued;clearPending();
        if(target==null||args==null||!hadPlay||target!=registered){cancel("launcher changed before animation");return;}
        writing=true;
        try{NativeReflection.invoke(prepare,target,args);NativeReflection.invoke(play,target,delay);state("played","native launcher prepare/play delivered after scene restore; gesture="+generation);}
        catch(Throwable t){makeVisible(target);state("failed",t.getClass().getSimpleName()+": "+t.getMessage());}
        finally{writing=false;}
    }
    void cancel(){cancel("gesture cancelled or visuals released without native takeover");}
    private void cancel(String reason){main.removeCallbacks(timeout);Object old=pending;clearPending();gate.cancel();
        if(old!=null){writing=true;try{makeVisible(old);}finally{writing=false;}state("cancelled",reason);}}
    private void makeVisible(Object proxy){try{if(proxy instanceof IInterface i&&i.asBinder().isBinderAlive())NativeReflection.invoke(reset,proxy,false);}catch(Throwable ignored){}}
    private void clearPending(){pending=null;prepareArgs=null;playQueued=false;playDelay=0;}
    private void scheduleTimeout(){main.removeCallbacks(timeout);main.postDelayed(timeout,3000);}
    private void state(String state,String detail){reporter.desktopAnimationState=state;reporter.desktopAnimationDetail=detail;reporter.publish();}
    private void hook(Method method,XposedInterface.Hooker hooker){handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker));}
}
