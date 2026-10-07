package com.zhuanz.flymemotion.hook;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.SurfaceControl;
import java.util.function.LongConsumer;

/** Selected producer's completed buffer + owned present-fence duplicate, never pixel data. */
public final class NativeSurfaceFrame implements AutoCloseable {
    private static boolean loaded;
    private static Handler worker;
    private long registration;
    private final long startedNanos;
    private final LongConsumer completed;
    private volatile boolean closed;
    public static synchronized void load(String path) throws Exception {
        if(loaded)return;
        initializeLibrary(path);
    }
    /** Production uses the owning module ClassLoader, not a guessed extracted-library path. */
    public static synchronized void loadPackaged() throws Exception {
        if(loaded)return;
        initializeLibrary(null);
    }
    private static void initializeLibrary(String probePath)throws Exception {
        if(probePath==null)System.loadLibrary("flymesurfaceframe");else System.load(probePath);
        initialize();
        HandlerThread thread=new HandlerThread("FlymeMotion-surface-frame");thread.start();worker=new Handler(thread.getLooper());loaded=true;
    }
    public NativeSurfaceFrame(SurfaceControl surface,LongConsumer completed) {
        if(!loaded||!surface.isValid())throw new IllegalStateException("SurfaceStats unavailable");
        this.completed=completed;startedNanos=System.nanoTime();
        registration=register(((Number)NativeReflection.get(surface,"mNativeObject")).longValue(),this);
    }
    /** Called by native completion, copies no buffer and posts all waiting off Binder/WM. */
    @SuppressWarnings("unused")
    public void onFrame(long frame,long previous,long latch,int fd) {
        ParcelFileDescriptor fence=ParcelFileDescriptor.adoptFd(fd);
        if(!worker.post(()->{
            try(fence){
                if(closed||frame<=previous||latch<startedNanos)return;
                StructPollfd poll=new StructPollfd();poll.fd=fence.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
                if(Os.poll(new StructPollfd[]{poll},1000)>0 && (poll.revents&OsConstants.POLLIN)!=0
                        && (poll.revents&(OsConstants.POLLERR|OsConstants.POLLNVAL))==0 && !closed) completed.accept(frame);
            }catch(Exception failure){android.util.Log.w("FlymeMotion","Surface present fence failed",failure);}
        }))try{fence.close();}catch(Exception ignored){}
    }
    @Override public void close(){closed=true;if(registration!=0){unregister(registration);registration=0;}}
    private static native void initialize();
    private static native long register(long surface,Object receiver);
    private static native void unregister(long registration);
}
