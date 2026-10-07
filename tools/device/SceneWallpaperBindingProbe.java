package com.zhuanz.flymemotion.probe;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** Executes the production matcher on owned hidden layer fixtures. No pixels or native windows. */
public final class SceneWallpaperBindingProbe {
    public static final class Client implements IInterface {
        private final IBinder binder = new Binder();
        @Override public IBinder asBinder() { return binder; }
    }
    public static final class Animator { public SurfaceControl mSurfaceControl; }
    public static final class Window {
        public IInterface mClient = new Client(); public Animator mWinAnimator = new Animator();
        private final SurfaceControl server;
        Window(SurfaceControl server, SurfaceControl client) { this.server = server; mWinAnimator.mSurfaceControl = client; }
        public SurfaceControl getSurfaceControl() { return server; }
    }
    private static long layer(SurfaceControl surface) throws Exception {
        Method method = SurfaceControl.class.getDeclaredMethod("getLayerId"); method.setAccessible(true);
        return ((Number)method.invoke(surface)).longValue();
    }
    private static SurfaceControl owned(String name, SurfaceControl parent) throws Exception {
        var builder = new SurfaceControl.Builder().setName(name).setHidden(true);
        Method container = builder.getClass().getDeclaredMethod("setContainerLayer"); container.setAccessible(true); container.invoke(builder);
        if (parent != null) builder.setParent(parent);
        return builder.build();
    }
    public static void main(String[] args) throws Exception {
        int sdk = Build.VERSION.class.getField("SDK_INT").getInt(null);
        if (sdk != 37 || !"myron".equals(Build.DEVICE) || !Build.FINGERPRINT.contains("OS4.0.0.30.XPMCNXM"))
            throw new IllegalStateException("Target-only no-pixel binding probe");
        SurfaceControl server = owned("FlymeMotion-Owned-Binding-Server",null), client = null, buffer = null, replacement = null;
        try {
            client = owned("FlymeMotion-Owned-Binding-Client",server);
            buffer = owned("FlymeMotion-Owned-Binding-Buffer",client);
            replacement = owned("FlymeMotion-Owned-Binding-Replacement",server);
            Class<?> type = Class.forName("com.zhuanz.flymemotion.hook.SystemServerSceneHooks");
            var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            Object hooks = ctor.newInstance(null,SurfaceControl.class.getClassLoader(),null);
            Field frames = type.getDeclaredField("producerFrames"); frames.setAccessible(true);
            Method generation = type.getDeclaredMethod("producerGeneration",Object.class,int.class); generation.setAccessible(true);
            Window window = new Window(server,client);
            Bundle frame = new Bundle(); frame.putInt("which",2); frame.putLong("generation",1);
            frame.putBoolean("drawn",true); frame.putLong("drawSequence",1);
            frame.putBinder("client",window.mClient.asBinder()); frame.putInt("containerLayer",(int)layer(client));
            frame.putInt("bufferLayer",(int)layer(buffer)); frames.set(hooks,List.of(frame));
            boolean distinct = layer(server)!=layer(client) && layer(client)!=layer(buffer);
            boolean currentAccepted = ((Number)generation.invoke(hooks,window,2)).longValue()==1;
            window.mWinAnimator.mSurfaceControl = replacement;
            boolean oldRootRejected = ((Number)generation.invoke(hooks,window,2)).longValue()==0;
            window.mWinAnimator.mSurfaceControl = client; frame.putBinder("client",new Binder());
            boolean oldClientRejected = ((Number)generation.invoke(hooks,window,2)).longValue()==0;
            frame.putBinder("client",window.mClient.asBinder()); frame.putBoolean("drawn",false);
            boolean undrawnRejected = ((Number)generation.invoke(hooks,window,2)).longValue()==0;
            frame.putBoolean("drawn",true);
            boolean wrongRoleRejected = ((Number)generation.invoke(hooks,window,1)).longValue()==0;
            JSONObject result = new JSONObject().put("probe","owned-hidden-production-wallpaper-binding")
                    .put("distinctLayerLevels",distinct).put("currentRootAccepted",currentAccepted)
                    .put("replacedRootRejected",oldRootRejected).put("oldWindowClientRejected",oldClientRejected)
                    .put("undrawnRejected",undrawnRejected).put("wrongRoleRejected",wrongRoleRejected)
                    .put("productionMethodExecuted",true).put("capturedPixels",false).put("nativeWindowsChanged",false)
                    .put("result",distinct&&currentAccepted&&oldRootRejected&&oldClientRejected&&undrawnRejected&&wrongRoleRejected?"passed":"failed");
            System.out.println(result);
            if (args.length==0 && !currentAccepted) throw new IllegalStateException("Current registered client root rejected");
            if (!oldRootRejected || !oldClientRejected || !undrawnRejected || !wrongRoleRejected) throw new IllegalStateException("Invalid frame accepted");
        } finally {
            try (var t = new SurfaceControl.Transaction()) {
                Method remove = t.getClass().getDeclaredMethod("remove",SurfaceControl.class); remove.setAccessible(true);
                remove.invoke(t,server); t.apply();
            }
            if (replacement!=null) replacement.release(); if (buffer!=null) buffer.release(); if (client!=null) client.release(); server.release();
        }
        System.exit(0);
    }
}
