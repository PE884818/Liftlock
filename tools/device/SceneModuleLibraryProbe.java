package com.zhuanz.flymemotion.probe;

import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.ZipFile;

/** Module-like in-memory loader; observes no user surface and reads no pixels. */
public final class SceneModuleLibraryProbe {
    public static void main(String[] args) {
        try(var apk=new ZipFile(args[0])) {
            var dex=new ArrayList<ByteBuffer>();
            for(int i=1;;i++) {
                var entry=apk.getEntry(i==1?"classes.dex":"classes"+i+".dex");
                if(entry==null)break;
                try(var in=apk.getInputStream(entry)){dex.add(ByteBuffer.wrap(in.readAllBytes()));}
            }
            ClassLoader loader;
            if(args.length>2 && "lspd".equals(args[2])) {
                var type=Class.forName("n1");
                var ctor=type.getDeclaredConstructor(ByteBuffer[].class,String.class,ClassLoader.class,String.class,String.class,int.class);
                ctor.setAccessible(true);
                loader=(ClassLoader)ctor.newInstance(dex.toArray(ByteBuffer[]::new),args[0]+"!/lib/arm64-v8a",Object.class.getClassLoader(),args[0],"com.zhuanz.flymemotion",101);
            } else loader=new InMemoryDexClassLoader(dex.toArray(ByteBuffer[]::new),
                args[0]+"!/lib/arm64-v8a",Object.class.getClassLoader());
            var bridge=loader.loadClass("com.zhuanz.flymemotion.hook.NativeSurfaceFrame");
            if("packaged".equals(args[1]))bridge.getMethod("loadPackaged").invoke(null);
            else bridge.getMethod("load",String.class).invoke(null,args[1]);
            System.out.println("MODULE_NATIVE_LOAD_OK");
            if(args.length>3 && "frame".equals(args[3])) {
                android.view.SurfaceControl surface=new android.view.SurfaceControl.Builder().setName("FlymeMotion-Owned-Module-Loader").setHidden(true).setBufferSize(16,16).build();
                var latch=new java.util.concurrent.CountDownLatch(1);
                var frame=new java.util.concurrent.atomic.AtomicLong(-1);
                var observer=(AutoCloseable)bridge.getConstructor(android.view.SurfaceControl.class,java.util.function.LongConsumer.class)
                    .newInstance(surface,(java.util.function.LongConsumer)n->{frame.set(n);latch.countDown();});
                try(observer;var buffer=android.hardware.HardwareBuffer.create(16,16,android.hardware.HardwareBuffer.RGBA_8888,1,
                        android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
                    try(var t=new android.view.SurfaceControl.Transaction()) {
                        var stack=t.getClass().getDeclaredMethod("setLayerStack",android.view.SurfaceControl.class,int.class);stack.setAccessible(true);stack.invoke(t,surface,100823);
                        t.setVisibility(surface,true).apply();
                    }
                    if(latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS))throw new IllegalStateException("Metadata qualified as a frame");
                    try(var t=new android.view.SurfaceControl.Transaction()) {
                        var set=t.getClass().getDeclaredMethod("setBuffer",android.view.SurfaceControl.class,android.hardware.HardwareBuffer.class);set.setAccessible(true);set.invoke(t,surface,buffer);t.apply();
                    }
                    if(!latch.await(3,java.util.concurrent.TimeUnit.SECONDS)||frame.get()!=100)throw new IllegalStateException("Module frame proof missing");
                    System.out.println("MODULE_NATIVE_PRESENTED_FRAME_OK frame="+frame.get());
                }finally{try(var t=new android.view.SurfaceControl.Transaction()){t.reparent(surface,null).apply();}finally{surface.release();}}
            }
        }catch(Throwable failure){failure.printStackTrace(System.out);System.exit(1);}
        System.exit(0);
    }
}
