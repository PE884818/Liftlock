package com.zhuanz.flymemotion.probe;

import android.os.*;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Owned layers only. Phone path never imports or invokes screen capture. */
public final class SceneWallpaperExitProbe {
    static Object call(Object o,String n,Class<?>[] types,Object... args) throws Exception {
        var m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);
    }
    static void set(Object o,String n,Object value) throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
    static void require(boolean b,String s) {if(!b)throw new AssertionError(s);}
    public static final class Token {
        final SurfaceControl source,parent;
        Token(SurfaceControl s,SurfaceControl p){source=s;parent=p;}
        public SurfaceControl getSurfaceControl(){return source;}
        public SurfaceControl getParentSurfaceControl(){return parent;}
        public boolean isVisibleRequested(){return true;}
        public boolean isVisible(){return true;}
    }
    static SurfaceControl node(String name,SurfaceControl parent) throws Exception {
        var b=new SurfaceControl.Builder().setName("FlymeOwnedExit-"+name).setParent(parent).setHidden(true);
        call(b,"setContainerLayer",new Class<?>[0]);return b.build();
    }
    static void commit(SurfaceControl.Transaction t,Handler handler) throws Exception {
        var latch=new CountDownLatch(1);t.addTransactionCommittedListener(handler::post,latch::countDown);t.apply();
        require(latch.await(4,TimeUnit.SECONDS),"Commit missing");
    }
    static void assertOwnGreen(SurfaceControl root) throws Exception {
        // Emulator only, owned off-display 64x64 fixture; never called on the phone.
        var m=Class.forName("android.window.ScreenCapture").getDeclaredMethod("captureLayers",SurfaceControl.class,android.graphics.Rect.class,float.class);m.setAccessible(true);
        Object captured=m.invoke(null,root,new android.graphics.Rect(0,0,64,64),1f);
        require(captured!=null,"Owned emulator capture failed");
        var hardware=(android.graphics.Bitmap)call(captured,"asBitmap",new Class<?>[0]);
        var software=hardware.copy(android.graphics.Bitmap.Config.ARGB_8888,false);int c=software.getPixel(32,32);
        software.recycle();hardware.recycle();((android.hardware.HardwareBuffer)call(captured,"getHardwareBuffer",new Class<?>[0])).close();
        require(android.graphics.Color.green(c)>=252&&android.graphics.Color.red(c)<=3,"Retired LOCK reappeared in owned composite");
    }
    public static void main(String[] args) throws Exception {
        int runtimeSdk=Build.VERSION.class.getField("SDK_INT").getInt(null);
        boolean emulator=Build.HARDWARE.equals("ranchu")&&runtimeSdk==34;
        require(emulator||"myron".equals(Build.DEVICE)&&runtimeSdk==37,"Unexpected target");
        var worker=new HandlerThread("FlymeOwnedExitProbe");worker.start();var handler=new Handler(worker.getLooper());
        var parent=node("OffDisplay",null);var lock=node("Lock",parent);var home=node("SharedHome",parent);
        var colors=new ArrayList<SurfaceControl>();
        Object mirrors=null;
        try {
            try(var t=new SurfaceControl.Transaction()) {
                call(t,"setLayerStack",new Class<?>[]{SurfaceControl.class,int.class},parent,100923);
                t.setVisibility(parent,true).setVisibility(lock,true).setVisibility(home,true).setLayer(home,0).setLayer(lock,1);
                if(emulator) {
                    for(var source:new SurfaceControl[]{lock,home}) {
                        var builder=new SurfaceControl.Builder().setName("FlymeOwnedExit-Color").setParent(source).setHidden(true);
                        call(builder,"setColorLayer",new Class<?>[0]);var color=builder.build();colors.add(color);
                        call(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},color,source==lock?new float[]{1,0,0}:new float[]{0,1,0});
                        call(t,"setCrop",new Class<?>[]{SurfaceControl.class,android.graphics.Rect.class},color,new android.graphics.Rect(0,0,64,64));t.setVisibility(color,true);
                    }
                }
                commit(t,handler);
            }
            var type=Class.forName("com.zhuanz.flymemotion.hook.ServerWallpaperMirror");
            var ctor=type.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),Handler.class);ctor.setAccessible(true);
            mirrors=ctor.newInstance(null,handler);final Object mirrorHelper=mirrors;
            call(mirrors,"exclusiveRole",new Class<?>[]{Predicate.class},(Predicate<Object>)token->((Token)token).source==lock);
            // Exclusive source did not detach: successful retirement must still hide it.
            call(mirrors,"ensure",new Class<?>[]{Object.class,long.class,boolean.class},new Token(lock,parent),1L,true);
            try(var t=new SurfaceControl.Transaction()) {
                call(mirrors,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},t,true,(Runnable)()->{});commit(t,handler);
            }
            if(emulator)assertOwnGreen(parent);
            int lockId=((Number)call(lock,"getLayerId",new Class<?>[0])).intValue();
            require(!(boolean)call(mirrors,"visibility",new Class<?>[]{int.class,boolean.class},lockId,true),"Non-detached LOCK escaped retirement");
            var stateType=Class.forName("com.zhuanz.flymemotion.hook.SceneSurfaceState");
            var stateCtor=stateType.getDeclaredConstructor(Class.forName("com.zhuanz.flymemotion.hook.MotionModule"),ClassLoader.class,BooleanSupplier.class);stateCtor.setAccessible(true);
            Object states=stateCtor.newInstance(null,SceneWallpaperExitProbe.class.getClassLoader(),(BooleanSupplier)()->false);
            final int[] enforced={0};
            call(states,"enforceRetiredSources",new Class<?>[]{Consumer.class},(Consumer<SurfaceControl.Transaction>)t->{
                try {call(mirrorHelper,"enforceExitVisibility",new Class<?>[]{SurfaceControl.Transaction.class},t);enforced[0]++;}
                catch(Exception e){throw new RuntimeException(e);}
            });
            // The returned parcel has native SHOW but no Java pending map, matching Binder/BLAST.
            try(var original=new SurfaceControl.Transaction()) {
                original.setVisibility(lock,true);var parcel=Parcel.obtain();
                try {
                    original.writeToParcel(parcel,0);parcel.setDataPosition(0);
                    try(var remote=SurfaceControl.Transaction.CREATOR.createFromParcel(parcel);var merged=new SurfaceControl.Transaction()) {
                        merged.merge(remote);
                        call(states,"reconcileExitVisibility",new Class<?>[]{SurfaceControl.Transaction.class,Map.class},merged,null);
                        commit(merged,handler);
                    }
                } finally {parcel.recycle();}
            }
            if(emulator)assertOwnGreen(parent);
            require(enforced[0]==1,"Unobserved parcelled transaction bypassed invariant");
            call(mirrors,"ensure",new Class<?>[]{Object.class,long.class,boolean.class},new Token(home,parent),2L,false);
            try(var t=new SurfaceControl.Transaction()) {
                call(mirrors,"restore",new Class<?>[]{SurfaceControl.Transaction.class,boolean.class,Runnable.class},t,true,(Runnable)()->{});commit(t,handler);
            }
            if(emulator)assertOwnGreen(parent);
            int homeId=((Number)call(home,"getLayerId",new Class<?>[0])).intValue();
            require((boolean)call(mirrors,"visibility",new Class<?>[]{int.class,boolean.class},homeId,true),"Shared HOME was hidden");
            require((int)call(mirrors,"retiredSourceCount",new Class<?>[0])==1,"Incorrect source roles");
            call(mirrors,"clearExitSources",new Class<?>[0]);
            require((boolean)call(mirrors,"visibility",new Class<?>[]{int.class,boolean.class},lockId,true),"New lock cannot show wallpaper");
            System.out.println("{\"result\":\"passed\",\"unobservedParcelMergeSubmitted\":true,\"exclusiveNotDetachedRetired\":true,\"sharedHomePreserved\":true,\"phonePixelsCaptured\":false,\"actualUserWallpaperTested\":false}");
        } finally {
            if(mirrors!=null)call(mirrors,"clearExitSources",new Class<?>[0]);
            try(var t=new SurfaceControl.Transaction()){for(var c:colors)t.reparent(c,null);t.reparent(lock,null).reparent(home,null).reparent(parent,null);commit(t,handler);}
            for(var c:colors)c.release();lock.release();home.release();parent.release();worker.quitSafely();
        }
    }
}
