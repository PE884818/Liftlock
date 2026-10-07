package com.zhuanz.flymemotion.probe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Executes production restore helpers on OWNED off-display surfaces, never real WM windows.
 * No Xposed hooks are installed; the API is supplied only to resolve helper constructor types.
 * Proves the compositor/helper transaction boundary, not real sleep/launcher/auth callbacks.
 */
public final class SceneRestoreProbe {
    private static final ArrayList<SurfaceControl> owned = new ArrayList<>();
    private static Method crop, capture;
    private static Handler handler;
    private static boolean sdk34Fixture;
    private static Object invoke(Object obj, String name, Class<?>[] types, Object... args) throws Exception {
        Method m=obj.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(obj,args);
    }
    private static Field field(Object obj,String name) throws Exception {
        Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static SurfaceControl node(String name,SurfaceControl parent,boolean color) throws Exception {
        var b=new SurfaceControl.Builder().setName("FlymeRestoreProbe-"+name).setHidden(true).setParent(parent);
        invoke(b,color?"setColorLayer":"setContainerLayer",new Class<?>[0]);
        var s=b.build();owned.add(s);return s;
    }
    private record Fixture(SurfaceControl root,SurfaceControl shade,SurfaceControl source,SurfaceControl home,SurfaceControl mirror) {}
    public static final class ProbeToken {
        private final SurfaceControl source,parent;
        private boolean visible=true;
        private boolean visibleRequested=true;
        ProbeToken(SurfaceControl source,SurfaceControl parent){this.source=source;this.parent=parent;}
        public SurfaceControl getSurfaceControl(){return source;}
        public SurfaceControl getParentSurfaceControl(){return parent;}
        public boolean isVisible(){return visible;}
        public boolean isVisibleRequested(){return visibleRequested;}
    }
    private static Fixture fixture() throws Exception {
        var root=node("OffDisplay",null,false);var shade=node("Shade",root,false);
        var source=node("LockToken",root,false);var red=node("LockBuffer",source,true);var home=node("HomeBuffer",root,true);
        Method mirrorMethod=SurfaceControl.class.getDeclaredMethod("mirrorSurface",SurfaceControl.class);mirrorMethod.setAccessible(true);
        var mirror=(SurfaceControl)mirrorMethod.invoke(null,source);owned.add(mirror);
        try(var t=new SurfaceControl.Transaction()) {
            invoke(t,"setLayerStack",new Class<?>[]{SurfaceControl.class,int.class},root,100733);
            for(var s:new SurfaceControl[]{root,shade,source,red,home}) {crop.invoke(t,s,new Rect(0,0,64,64));t.setVisibility(s,true);}
            invoke(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},red,new float[]{1,0,0});
            invoke(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},home,new float[]{0,1,0});
            t.setLayer(home,0).setLayer(source,1).setLayer(shade,2).reparent(mirror,shade).setLayer(mirror,0).setVisibility(mirror,true);
            if(!sdk34Fixture)t.reparent(source,null);commit(t);
        }
        return new Fixture(root,shade,source,home,mirror);
    }
    private static Object serverMirror(Fixture f) throws Exception {
        Class<?> type=Class.forName("com.zhuanz.flymemotion.hook.ServerWallpaperMirror");
        Constructor<?> c=type.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),Handler.class);c.setAccessible(true);
        Object result=c.newInstance(null,handler);
        field(result,"source").set(result,f.source);field(result,"nativeParent").set(result,f.root);
        field(result,"mirror").set(result,f.mirror);field(result,"detached").setBoolean(result,true);
        return result;
    }
    @SuppressWarnings("unchecked") private static Object surfaceState(Fixture f) throws Exception {
        Class<?> type=Class.forName("com.zhuanz.flymemotion.hook.SceneSurfaceState");
        Constructor<?> c=type.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),ClassLoader.class,BooleanSupplier.class);
        c.setAccessible(true);Object result=c.newInstance(null,type.getClassLoader(),(BooleanSupplier)()->false);
        int id=((Number)invoke(f.home,"getLayerId",new Class<?>[0])).intValue();
        ((Map<Integer,SurfaceControl>)field(result,"promoted").get(result)).put(id,f.home);
        ((Map<Integer,Boolean>)field(result,"nativeDesired").get(result)).put(id,false);
        return result;
    }
    private static void commit(SurfaceControl.Transaction t) throws Exception {
        CountDownLatch done=new CountDownLatch(1);t.addTransactionCommittedListener(handler::post,done::countDown);t.apply();
        if(!done.await(2,TimeUnit.SECONDS))throw new AssertionError("Owned transaction did not commit");
    }
    private static int pixel(SurfaceControl root) throws Exception {
        Object result=capture.invoke(null,root,new Rect(0,0,64,64),1f);
        if(result==null)throw new AssertionError("captureLayers unavailable");
        Bitmap hardware=(Bitmap)invoke(result,"asBitmap",new Class<?>[0]);
        Bitmap software=hardware.copy(Bitmap.Config.ARGB_8888,false);
        int value=software.getPixel(32,32);software.recycle();hardware.recycle();
        ((android.hardware.HardwareBuffer)invoke(result,"getHardwareBuffer",new Class<?>[0])).close();return value;
    }
    /** SDK34 owned compositor check for the exact production independent guard. */
    private static void coverRegression(JSONObject report) throws Exception {
        Fixture f=fixture(); Object mirrors=serverMirror(f);
        // Preserve a live producer on another owned stack: it is absent from the return tree
        // while its independent mirror is still live (SDK34 cannot detach a producer root).
        SurfaceControl producerRoot=node("CoverProducerRoot",null,false);
        try(var t=new SurfaceControl.Transaction()) {
            invoke(t,"setLayerStack",new Class<?>[]{SurfaceControl.class,int.class},producerRoot,100735);
            t.setVisibility(producerRoot,true).reparent(f.source,producerRoot);commit(t);
        }
        Object states=surfaceState(f);
        invoke(states,"filterExitVisibility",new Class<?>[]{java.util.function.BiPredicate.class,java.util.function.IntFunction.class},
            (java.util.function.BiPredicate<Integer,Boolean>)(id,wanted)-> {try{return (boolean)invoke(mirrors,"visibility",new Class<?>[]{int.class,boolean.class},id,wanted);}catch(Exception e){throw new RuntimeException(e);}},
            (java.util.function.IntFunction<SurfaceControl>)id-> {try{return (SurfaceControl)invoke(mirrors,"exitSurface",new Class<?>[]{int.class},id);}catch(Exception e){throw new RuntimeException(e);}});
        Class<?> type=Class.forName("com.zhuanz.flymemotion.hook.ServerSceneCover");
        var constructor=type.getDeclaredConstructor(Handler.class);constructor.setAccessible(true);
        Object cover=constructor.newInstance(handler);CountDownLatch bound=new CountDownLatch(1);
        invoke(cover,"bind",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class,int.class,int.class,Runnable.class},
                f.source,f.root,1,64,64,(Runnable)bound::countDown);
        require(bound.await(2,TimeUnit.SECONDS),"Guard binding did not commit");
        try(var t=new SurfaceControl.Transaction()) {
            t.setVisibility(f.source,false).setAlpha(f.mirror,0);
            var batch=new java.util.LinkedHashMap<Integer,Boolean>();batch.put(((Number)invoke(f.source,"getLayerId",new Class<?>[0])).intValue(),false);
            invoke(states,"reconcileExitVisibility",new Class<?>[]{SurfaceControl.Transaction.class,Map.class},t,batch);commit(t);
        }
        require(pixel(f.root)==Color.RED,"Not-ready frame lost full independent cover");
        report.put("independentCoverSurvivesUiAlphaZero",true);
        invoke(cover,"reveal",new Class<?>[0]);try(var t=new SurfaceControl.Transaction()){commit(t);}
        int home=pixel(f.root);require(Color.green(home)>=252&&Color.red(home)<=3,"Ready guard did not reveal clear HOME");
        try(var t=new SurfaceControl.Transaction()) {
            t.setVisibility(f.home,false);
            invoke(cover,"protect",new Class<?>[]{SurfaceControl.Transaction.class},t);commit(t);
        }
        require(pixel(f.root)==Color.RED,"Destructive return txn exposed black before guard");
        report.put("returnHideAndGuardAreAtomic",true);
        try(var delayedUi=new SurfaceControl.Transaction()) {
            delayedUi.setAlpha(f.mirror,0).setVisibility(f.mirror,false);commit(delayedUi);
        }
        require(pixel(f.root)==Color.RED,"Delayed UI txn removed server-owned cover");
        report.put("lateUiTransactionCannotRemoveGuard",true);
        try(var t=new SurfaceControl.Transaction()){t.setVisibility(f.home,true).setVisibility(f.shade,false);commit(t);}
        require(pixel(f.root)==Color.RED,"Native Shade hide removed the independent guard");
        report.put("nativeShadeHideCannotRemoveGuard",true);
        CountDownLatch retired=new CountDownLatch(1);
        try(var t=new SurfaceControl.Transaction()) {
            invoke(cover,"retire",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},t,(Runnable)retired::countDown);
            invoke(mirrors,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},t,true,(Runnable)()->{});
            require(!retired.await(25,TimeUnit.MILLISECONDS),"Guard retired before restore submission");
            commit(t);require(retired.await(2,TimeUnit.SECONDS),"Guard retirement has no committed acknowledgement");
        }
        require(pixel(f.root)==home,"Atomic retirement restored LOCK over HOME");
        report.put("guardRetirementWaitsForAtomicRestore",true);
    }
    private static void replacementAndSharedRegression(JSONObject report) throws Exception {
        Fixture f=fixture();Object original=serverMirror(f);
        field(original,"token").set(original,new ProbeToken(f.source,f.root));
        SurfaceControl provider=node("NewProducerRoot",null,false),next=node("NewLockToken",provider,false),blue=node("NewLockBuffer",next,true);
        try(var t=new SurfaceControl.Transaction()) {
            invoke(t,"setLayerStack",new Class<?>[]{SurfaceControl.class,int.class},provider,100736);
            t.setVisibility(provider,true).setVisibility(next,true).setVisibility(blue,true);
            crop.invoke(t,blue,new Rect(0,0,64,64));invoke(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},blue,new float[]{0,0,1});commit(t);
        }
        invoke(original,"ensure",new Class<?>[]{Object.class,long.class},new ProbeToken(next,provider),123L);
        SurfaceControl replacement=(SurfaceControl)field(original,"mirror").get(original);owned.add(replacement);
        long generation=((Number)invoke(original,"generation",new Class<?>[0])).longValue();
        try(var t=new SurfaceControl.Transaction()){t.reparent(replacement,f.shade).setLayer(replacement,3).setVisibility(replacement,true);commit(t);}
        require((boolean)invoke(original,"acknowledge",new Class<?>[]{long.class,long.class,long.class},123L,generation,
                ((Number)invoke(replacement,"getLayerId",new Class<?>[0])).longValue()),"Replacement acknowledgement rejected");
        try(var t=new SurfaceControl.Transaction()){t.setVisibility(f.shade,false);commit(t);}
        int sample=pixel(f.root);require(Color.green(sample)>=252&&Color.red(sample)<=3,"Replacement acknowledgement restored retired LOCK for HOME sampling");
        report.put("replacementDoesNotUseCancelRestoration",true);
        CountDownLatch restored=new CountDownLatch(1);invoke(original,"restore",new Class<?>[]{Runnable.class},(Runnable)restored::countDown);
        require(restored.await(2,TimeUnit.SECONDS),"Replacement source cleanup not committed");
        invoke(original,"clearExitSources",new Class<?>[0]);
        Fixture shared=fixture();Class<?> type=Class.forName("com.zhuanz.flymemotion.hook.ServerWallpaperMirror");
        var constructor=type.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),Handler.class);constructor.setAccessible(true);
        Object helper=constructor.newInstance(null,handler);
        invoke(helper,"ensure",new Class<?>[]{Object.class,long.class,boolean.class},new ProbeToken(shared.source,shared.root),124L,false);
        SurfaceControl sharedMirror=(SurfaceControl)field(helper,"mirror").get(helper);owned.add(sharedMirror);
        long sg=((Number)invoke(helper,"generation",new Class<?>[0])).longValue();
        invoke(helper,"acknowledge",new Class<?>[]{long.class,long.class,long.class},124L,sg,
                ((Number)invoke(sharedMirror,"getLayerId",new Class<?>[0])).longValue());
        invoke(helper,"detachWhenReady",new Class<?>[]{boolean.class,boolean.class,Runnable.class},true,true,(Runnable)()->{});
        require(!(boolean)field(helper,"detached").get(helper),"Shared HOME/LOCK producer was detached as exclusive LOCK");
        require((boolean)invoke(helper,"ready",new Class<?>[0]),"Shared source never became usable");
        int sid=((Number)invoke(shared.source,"getLayerId",new Class<?>[0])).intValue();
        require(!(boolean)invoke(helper,"visibility",new Class<?>[]{int.class,boolean.class},sid,false),"Shared source native visibility was overridden");
        CountDownLatch sr=new CountDownLatch(1);try(var t=new SurfaceControl.Transaction()) {
            invoke(helper,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},t,true,(Runnable)sr::countDown);commit(t);
        }
        require(sr.await(2,TimeUnit.SECONDS),"Shared restore did not commit");
        require((boolean)invoke(helper,"visibility",new Class<?>[]{int.class,boolean.class},sid,true),"Shared HOME was retired as LOCK");
        report.put("sharedHomeLockSourceRetainsNativeRole",true);
        Fixture roleFlip=fixture();Object flip=serverMirror(roleFlip);field(flip,"token").set(flip,new ProbeToken(roleFlip.source,roleFlip.root));
        invoke(flip,"ensure",new Class<?>[]{Object.class,long.class,boolean.class},new ProbeToken(roleFlip.source,roleFlip.root),125L,false);
        SurfaceControl fm=(SurfaceControl)field(flip,"mirror").get(flip);owned.add(fm);
        invoke(flip,"acknowledge",new Class<?>[]{long.class,long.class,long.class},125L,
            ((Number)invoke(flip,"generation",new Class<?>[0])).longValue(),((Number)invoke(fm,"getLayerId",new Class<?>[0])).longValue());
        CountDownLatch done=new CountDownLatch(1);
        try(var t=new SurfaceControl.Transaction()) {
            invoke(flip,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},t,true,(Runnable)done::countDown);
            t.setVisibility(roleFlip.shade,false);commit(t);
        }
        require(done.await(2,TimeUnit.SECONDS),"Role-change restore did not commit");
        require(pixel(roleFlip.root)==Color.RED,"Exclusive-to-shared role change hid current HOME producer");
        report.put("exclusiveToSharedRoleDoesNotRetireHome",true);

    }
    private static void require(boolean condition,String text) {if(!condition)throw new AssertionError(text);}
    public static void main(String[] args) {
        JSONObject report=new JSONObject();HandlerThread thread=new HandlerThread("FlymeRestoreProbe");thread.start();handler=new Handler(thread.getLooper());
        try {
            int runtimeSdk=Build.VERSION.class.getField("SDK_INT").getInt(null);
            report.put("buildSdk",runtimeSdk).put("hardware",Build.class.getField("HARDWARE").get(null)).put("fingerprint",Build.class.getField("FINGERPRINT").get(null));
            boolean emulator=runtimeSdk==34&&Build.HARDWARE.equals("ranchu")
                    &&Build.FINGERPRINT.startsWith("Android/sdk_phone64_x86_64/emu64x:");
            require(emulator?Build.HARDWARE.contains("ranchu")&&runtimeSdk==34:
                "myron".equals(Build.DEVICE)&&runtimeSdk==37&&"OS4.0.0.30.XPMCNXM".equals(Build.VERSION.INCREMENTAL),"Unexpected target");
            sdk34Fixture=emulator;
            report.put("detachedProducerMirrorValidated",!emulator);
            report.put("environment",emulator?"SDK34 owned emulator surfaces":"myron SDK37 owned surfaces");
            crop=SurfaceControl.Transaction.class.getDeclaredMethod("setCrop",SurfaceControl.class,Rect.class);crop.setAccessible(true);
            capture=Class.forName(emulator?"android.window.ScreenCapture":"android.window.ScreenCaptureInternal").getDeclaredMethod("captureLayers",SurfaceControl.class,Rect.class,float.class);capture.setAccessible(true);
            if(sdk34Fixture) { coverRegression(report); replacementAndSharedRegression(report); }
            for(boolean handoff:new boolean[]{false,true}) {
                Fixture f=fixture();Object states=surfaceState(f),mirror=serverMirror(f);
                require(pixel(f.root)==Color.RED,"Full mirror fixture must cover HOME");
                if(handoff) try(var t=new SurfaceControl.Transaction()){t.setVisibility(f.source,false).setAlpha(f.mirror,0);commit(t);}
                int handoffBaseline=handoff?pixel(f.root):0;
                if(handoff) {
                    // OEM capture color conversion slightly changes solid green (e.g. ff02ff00).
                    // Compare restoration against that actual capture, not an assumed sRGB byte.
                    require(Color.alpha(handoffBaseline)==255&&Color.green(handoffBaseline)>=252
                        &&Color.red(handoffBaseline)<=3&&Color.blue(handoffBaseline)<=3,"Native handoff fixture does not show HOME");
                    report.put("handoffBaselinePixel",String.format("%08x",handoffBaseline));
                }
                CountDownLatch restored=new CountDownLatch(1);
                try(var t=new SurfaceControl.Transaction()) {
                    invoke(states,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class},t,handoff);
                    invoke(mirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},t,(Runnable)restored::countDown);
                    // Models native Shade disappearance in the SAME frame as source restoration.
                    t.setVisibility(f.shade,false);
                    require(!restored.await(35,TimeUnit.MILLISECONDS),"Restore callback fired before caller applied shared transaction");
                    commit(t);require(restored.await(2,TimeUnit.SECONDS),"Restore acknowledgement missing");
                }
                int after=pixel(f.root);
                report.put(handoff?"handoffPixel":"cancelPixel",String.format("%08x",after));
                require(after==(handoff?handoffBaseline:Color.RED),handoff?"Handoff changed actual HOME pixels":"Cancellation exposed HOME after Shade hide");
                report.put(handoff?"handoffKeepsHomePixels":"cancelKeepsLockPixels",true);
            }
            // An empty mirror must also wait for the caller's scene transaction.
            Class<?> mirrorType=Class.forName("com.zhuanz.flymemotion.hook.ServerWallpaperMirror");
            Constructor<?> emptyConstructor=mirrorType.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),Handler.class);
            emptyConstructor.setAccessible(true);Object emptyMirror=emptyConstructor.newInstance(null,handler);
            CountDownLatch emptyRestored=new CountDownLatch(1);
            try(var t=new SurfaceControl.Transaction()) {
                invoke(emptyMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},t,(Runnable)emptyRestored::countDown);
                require(!emptyRestored.await(35,TimeUnit.MILLISECONDS),"Empty mirror acknowledged an unapplied scene transaction");
                commit(t);require(emptyRestored.await(2,TimeUnit.SECONDS),"Empty mirror commit acknowledgement missing");
            }
            report.put("emptyRestoreWaitsForCommit",true);
            // Native token intent is already hidden, while WindowAnimator's hide transaction
            // has not reached SF yet. A separate reparent must not expose this live producer
            // even for one frame (which also becomes the next HOME PassBlur input).
            Fixture pendingHide=fixture();Object pendingMirror=serverMirror(pendingHide);
            ProbeToken pendingToken=new ProbeToken(pendingHide.source,pendingHide.root);pendingToken.visible=false;pendingToken.visibleRequested=false;
            field(pendingMirror,"token").set(pendingMirror,pendingToken);
            try(var nativeHide=new SurfaceControl.Transaction();var restore=new SurfaceControl.Transaction()) {
                nativeHide.setVisibility(pendingHide.source,false);
                invoke(pendingMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},restore,(Runnable)()->{});
                restore.setVisibility(pendingHide.shade,false);commit(restore);
                int beforeNativeHide=pixel(pendingHide.root);
                report.put("pendingNativeHidePixel",String.format("%08x",beforeNativeHide));
                require(Color.green(beforeNativeHide)>=252&&Color.red(beforeNativeHide)<=3&&Color.blue(beforeNativeHide)<=3,
                    "Source reattachment exposed LOCK before native queued visibility committed");
                commit(nativeHide);
                require(pixel(pendingHide.root)==beforeNativeHide,"Native hide changed restored HOME pixels");
            }
            report.put("reattachHonorsPendingNativeHide",true);
            Fixture exit=fixture();Object exitMirror=serverMirror(exit);
            ProbeToken exitToken=new ProbeToken(exit.source,exit.root);field(exitMirror,"token").set(exitMirror,exitToken);
            // Both native flags can still be TRUE at a successful native unlock. The old
            // requested-state fix is not sufficient, and a SHOW may already be queued.
            try(var delayedShow=new SurfaceControl.Transaction();var restore=new SurfaceControl.Transaction()) {
                delayedShow.setVisibility(exit.source,true);
                restore.setAlpha(exit.mirror,0);
                try {
                    invoke(exitMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},restore,true,(Runnable)()->{});
                } catch(NoSuchMethodException oldVersion) {
                    invoke(exitMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},restore,(Runnable)()->{});
                }
                restore.setVisibility(exit.shade,false);commit(restore);
                int after=pixel(exit.root);report.put("nativeStillRequestedExitPixel",String.format("%08x",after));
                require(Color.green(after)>=252&&Color.red(after)<=3,"Successful exit restored a still-requested LOCK source over HOME");
                Object exitStates=surfaceState(exit);
                invoke(exitStates,"filterExitVisibility",new Class<?>[]{java.util.function.BiPredicate.class,java.util.function.IntFunction.class},
                    (java.util.function.BiPredicate<Integer,Boolean>)(id,wanted)-> {try{return (boolean)invoke(exitMirror,"visibility",new Class<?>[]{int.class,boolean.class},id,wanted);}catch(Exception e){throw new RuntimeException(e);}},
                    (java.util.function.IntFunction<SurfaceControl>)id-> {try{return (SurfaceControl)invoke(exitMirror,"exitSurface",new Class<?>[]{int.class},id);}catch(Exception e){throw new RuntimeException(e);}});
                var pending=new java.util.LinkedHashMap<Integer,Boolean>();
                pending.put(((Number)invoke(exit.source,"getLayerId",new Class<?>[0])).intValue(),true);
                invoke(exitStates,"reconcileExitVisibility",new Class<?>[]{SurfaceControl.Transaction.class,Map.class},delayedShow,pending);
                commit(delayedShow);
                require(pixel(exit.root)==after,"Previously constructed native SHOW leaked LOCK after handoff");
                report.put("lateNativeShowReconciledAtSubmission",true);
                invoke(exitMirror,"clearExitSources",new Class<?>[0]);
                try(var relock=new SurfaceControl.Transaction()){relock.setVisibility(exit.source,true);commit(relock);}
                require(pixel(exit.root)==Color.RED,"Next native lock failed to restore LOCK source");
                report.put("nextNativeLockRestoresWallpaper",true);
            }
            // In a native exit transition WallpaperWindowToken deliberately keeps client
            // visible=true after visibleRequested=false. There is no hide transaction yet.
            Fixture deferredHide=fixture();Object deferredMirror=serverMirror(deferredHide);
            ProbeToken deferredToken=new ProbeToken(deferredHide.source,deferredHide.root);
            deferredToken.visibleRequested=false;
            field(deferredMirror,"token").set(deferredMirror,deferredToken);
            try(var restore=new SurfaceControl.Transaction()) {
                try { invoke(deferredMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},restore,true,(Runnable)()->{}); }
                catch(NoSuchMethodException oldVersion) { invoke(deferredMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},restore,(Runnable)()->{}); }
                restore.setVisibility(deferredHide.shade,false);commit(restore);
            }
            int deferredPixel=pixel(deferredHide.root);
            report.put("deferredNativeHidePixel",String.format("%08x",deferredPixel));
            require(Color.green(deferredPixel)>=252&&Color.red(deferredPixel)<=3&&Color.blue(deferredPixel)<=3,
                "Deferred native transition exposed LOCK although visibleRequested=false; client visible=true is stale intent");
            report.put("reattachHonorsDeferredNativeHide",true);
            Fixture pendingShow=fixture();Object showMirror=serverMirror(pendingShow);
            ProbeToken showToken=new ProbeToken(pendingShow.source,pendingShow.root);
            showToken.visible=false; // Requested=true must win over a deferred hidden client.
            field(showMirror,"token").set(showMirror,showToken);
            try(var oldState=new SurfaceControl.Transaction()){oldState.setVisibility(pendingShow.source,false);commit(oldState);}
            // The reverse transition (cancel/AOD) must also honor native visible=true instead
            // of restoring the stale hidden surface bit left by the preceding unlock.
            try(var restore=new SurfaceControl.Transaction()) {
                invoke(showMirror,"restore",new Class<?>[]{SurfaceControl.Transaction.class,Runnable.class},restore,(Runnable)()->{});
                restore.setVisibility(pendingShow.shade,false);commit(restore);
            }
            require(pixel(pendingShow.root)==Color.RED,"Cancellation ignored the current native visible wallpaper intent");
            report.put("reattachHonorsPendingNativeShow",true);
            // SDK34 mirrorSurface stops updating after producer detachment; test its owned
            // source restoration here, reserve detached-producer/replacement checks for SDK37.
            if(!sdk34Fixture) {
            Fixture replacement=fixture();Object replacementMirror=serverMirror(replacement);
            SurfaceControl hiddenNativeParent=node("HiddenNativeParent",replacement.root,false);
            field(replacementMirror,"nativeParent").set(replacementMirror,hiddenNativeParent);
            // During an engine/Surface replacement the old native parent is already hidden,
            // but its detached live mirror still covers the return scene.
            invoke(replacementMirror,"ensure",new Class<?>[]{Object.class,long.class},null,777L);
            try(var t=new SurfaceControl.Transaction()){commit(t);}
            require(pixel(replacement.root)==Color.RED,"Temporary missing lock target dropped existing mirror coverage");
            report.put("missingTargetKeepsCover",true);
            SurfaceControl nextSource=node("ReplacementToken",hiddenNativeParent,false);
            SurfaceControl nextColor=node("ReplacementBuffer",nextSource,true);
            try(var t=new SurfaceControl.Transaction()) {
                crop.invoke(t,nextColor,new Rect(0,0,64,64));
                invoke(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},nextColor,new float[]{0,0,1});
                t.setVisibility(nextSource,true).setVisibility(nextColor,true);commit(t);
            }
            invoke(replacementMirror,"ensure",new Class<?>[]{Object.class,long.class},new ProbeToken(nextSource,hiddenNativeParent),777L);
            SurfaceControl nextMirror=(SurfaceControl)field(replacementMirror,"mirror").get(replacementMirror);owned.add(nextMirror);
            try(var t=new SurfaceControl.Transaction()){commit(t);}
            int awaiting=pixel(replacement.root);report.put("replacementAwaitingPixel",String.format("%08x",awaiting));
            require(awaiting==Color.RED,"Replacement restored old source before new cover committed");
            report.put("replacementKeepsOldCoverUntilAck",true);
            long nextGeneration=((Number)invoke(replacementMirror,"generation",new Class<?>[0])).longValue();
            long nextLayer=((Number)invoke(nextMirror,"getLayerId",new Class<?>[0])).longValue();
            try(var t=new SurfaceControl.Transaction()) {
                t.reparent(nextMirror,replacement.shade).setLayer(nextMirror,1).setVisibility(nextMirror,true);
                crop.invoke(t,nextMirror,new Rect(0,0,64,64));commit(t);
            }
            int nextPixels=pixel(replacement.root);
            require(Color.blue(nextPixels)>=252&&Color.red(nextPixels)<=3&&Color.green(nextPixels)<=3,"Replacement mirror is not the new live blue producer");
            require((boolean)invoke(replacementMirror,"acknowledge",new Class<?>[]{long.class,long.class,long.class},777L,nextGeneration,nextLayer),"New cover acknowledgement rejected");
            invoke(replacementMirror,"detachWhenReady",new Class<?>[]{boolean.class,boolean.class,Runnable.class},true,true,(Runnable)()->{});
            try(var t=new SurfaceControl.Transaction()){commit(t);}
            require(pixel(replacement.root)==nextPixels,"Retiring old source changed the committed new cover");
            report.put("replacementRetiresAfterNewCoverCommit",true);
            // WindowContainer may copy a new native handle into the SAME Java SurfaceControl.
            // A source lease must retain its own native handle and detect the changed producer.
            Fixture copied=fixture();
            Object copyMirror=emptyConstructor.newInstance(null,handler);
            invoke(copyMirror,"ensure",new Class<?>[]{Object.class,long.class},new ProbeToken(copied.source,copied.root),888L);
            SurfaceControl bound=(SurfaceControl)field(copyMirror,"mirror").get(copyMirror);owned.add(bound);
            long beforeGeneration=((Number)invoke(copyMirror,"generation",new Class<?>[0])).longValue();
            SurfaceControl replacementSource=node("CopiedClientToken",copied.root,false);
            invoke(copied.source,"copyFrom",new Class<?>[]{SurfaceControl.class,String.class},replacementSource,"Probe-WindowContainer-copy");
            invoke(copyMirror,"ensure",new Class<?>[]{Object.class,long.class},new ProbeToken(copied.source,copied.root),888L);
            SurfaceControl rebound=(SurfaceControl)field(copyMirror,"mirror").get(copyMirror);if(rebound!=bound)owned.add(rebound);
            require(((Number)invoke(copyMirror,"generation",new Class<?>[0])).longValue()>beforeGeneration,"Mutable source handle was mistaken for the existing producer");
            report.put("sourceCopyFromCreatesNewGeneration",true);
            CountDownLatch copyRestored=new CountDownLatch(1);
            invoke(copyMirror,"restore",new Class<?>[]{Runnable.class},(Runnable)copyRestored::countDown);
            require(copyRestored.await(2,TimeUnit.SECONDS),"Copied source lease failed to restore");
            }
            report.put("completed",true).put("productionHelpersExecuted",true).put("nativePhoneWindowsChanged",false)
                  .put("hooksInstalled",false).put("displayStack",100733).put("actualAodAndLauncherVerified",false);
        } catch(Throwable failure) {
            try {report.put("completed",false).put("error",failure.toString());} catch(Exception ignored){}
            failure.printStackTrace(System.err);
        } finally {
            try(var t=new SurfaceControl.Transaction()){for(var s:owned)if(s.isValid())t.reparent(s,null);t.apply();}
            for(var s:owned)if(s.isValid())s.release();thread.quitSafely();
            try {thread.join(1000);} catch(InterruptedException ignored) {Thread.currentThread().interrupt();}
        }
        System.out.println("FLYME_SCENE_RESTORE_PROBE "+report);
        System.out.flush();System.exit(report.optBoolean("completed")?0:1);
    }
}
