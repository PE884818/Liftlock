package com.zhuanz.flymemotion.probe;

import android.graphics.Rect;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Owned native objects only: actual VRI flag replacement, production sequence preservation. */
public final class SceneBufferResizeProbe {
    private static Field field(Class<?> cls,String name)throws Exception{
        Field f=cls.getDeclaredField(name);f.setAccessible(true);return f;
    }
    public static void main(String[] args)throws Exception{
        if(Build.VERSION.class.getField("SDK_INT").getInt(null)!=37||!"myron".equals(Build.DEVICE))throw new IllegalStateException("Target required");
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Object unsafe=field(unsafeClass,"theUnsafe").get(null);
        Method alloc=unsafeClass.getMethod("allocateInstance",Class.class);
        Class<?> vri=Class.forName("android.view.ViewRootImpl");Object root=alloc.invoke(unsafe,vri);
        field(vri,"mThread").set(root,Thread.currentThread());field(vri,"mDirty").set(root,new Rect());
        field(vri,"mWillDrawSoon").set(root,true); // suppress traversal; no real View or window
        Method report=vri.getDeclaredMethod("setReportNextDraw",boolean.class,String.class);report.setAccessible(true);
        report.invoke(root,true,"owned-fixture-buffer-resize");
        boolean first=field(vri,"mSyncBuffer").getBoolean(root);
        report.invoke(root,false,"owned-fixture-native-following-resize");
        boolean lost=!field(vri,"mSyncBuffer").getBoolean(root);
        Class<?> ws=Class.forName("com.android.server.wm.WindowState");Object window=alloc.invoke(unsafe,ws);
        Field buffer=field(ws,"mBufferSeqId"),sync=field(ws,"mSyncSeqId");
        buffer.setInt(window,21);sync.setInt(window,21);
        Class<?> helper=Class.forName("com.zhuanz.flymemotion.hook.TargetBufferDrawRequest");
        Method preserve=helper.getDeclaredMethod("preserve",Object.class);preserve.setAccessible(true);
        boolean changed=(boolean)preserve.invoke(null,window);
        boolean syncBuffer=buffer.getInt(window)>sync.getInt(window);
        report.invoke(root,syncBuffer,"owned-fixture-preserved-native-sequence");
        boolean retained=field(vri,"mSyncBuffer").getBoolean(root);
        boolean passed=first&&lost&&changed&&syncBuffer&&retained;
        System.out.println(new JSONObject().put("probe","owned-native-VRI-and-WindowState-no-window")
            .put("nativeFlagOverwriteReproduced",first&&lost).put("productionPreservationExecuted",changed)
            .put("preservedSequence",buffer.getInt(window)).put("syncBufferRetained",retained)
            .put("reportResizedExecuted",false).put("actualClientTested",false).put("capturedPixels",false)
            .put("result",passed?"passed":"failed"));
        if(!passed)throw new IllegalStateException("Sequence fixture failed");System.exit(0);
    }
}
