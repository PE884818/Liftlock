package com.zhuanz.flymemotion.probe;

import android.os.Binder;
import android.os.Build;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Native lifecycle queue fixture. Only new local objects and local fake clients; no windows/pixels. */
public final class SceneClientDrawProbe {
    private static Object client(Class<?> type, AtomicInteger sent) {
        Binder binder = new Binder();
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args) -> {
            if (method.getName().equals("asBinder")) return binder;
            if (method.getName().equals("scheduleTransaction")) { sent.incrementAndGet(); return null; }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy==args[0];
            if (method.getName().equals("toString")) return "FlymeMotion-Owned-FakeClient";
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        int sdk = Build.VERSION.class.getField("SDK_INT").getInt(null);
        if (sdk!=37 || !"myron".equals(Build.DEVICE) || !Build.FINGERPRINT.contains("OS4.0.0.30.XPMCNXM"))
            throw new IllegalStateException("Target-only local lifecycle fixture");
        Class<?> lm = Class.forName("com.android.server.wm.ClientLifecycleManager");
        var ctor=lm.getDeclaredConstructor();ctor.setAccessible(true);Object lifecycle=ctor.newInstance();
        Class<?> app=Class.forName("android.app.IApplicationThread");
        AtomicInteger sent=new AtomicInteger(),otherSent=new AtomicInteger();
        Object selected=client(app,sent),other=client(app,otherSent);
        Method enqueue=lm.getDeclaredMethod("getOrCreatePendingTransaction",app);enqueue.setAccessible(true);
        enqueue.invoke(lifecycle,selected);enqueue.invoke(lifecycle,other);
        boolean initiallyQueued=sent.get()==0&&otherSent.get()==0;
        Class<?> helper=Class.forName("com.zhuanz.flymemotion.hook.TargetDrawDispatcher");
        var helperCtor=helper.getDeclaredConstructor(ClassLoader.class);helperCtor.setAccessible(true);
        Object dispatcher=helperCtor.newInstance(lm.getClassLoader());
        Method dispatch=helper.getDeclaredMethod("dispatch",Object.class,Object.class);dispatch.setAccessible(true);
        boolean dispatched=(boolean)dispatch.invoke(dispatcher,lifecycle,selected);
        boolean delivered=sent.get()==1&&otherSent.get()==0;
        boolean noReplay=!(boolean)dispatch.invoke(dispatcher,lifecycle,selected)&&sent.get()==1;
        boolean independentOther=(boolean)dispatch.invoke(dispatcher,lifecycle,other)&&otherSent.get()==1;
        JSONObject result=new JSONObject().put("probe","native-local-client-lifecycle-queue")
                .put("initiallyQueued",initiallyQueued).put("selectedDispatched",dispatched).put("selectedDeliveredOnly",delivered)
                .put("noReplay",noReplay).put("otherClientRetained",independentOther)
                .put("nativeLifecycleImplementationExecuted",true).put("productionDispatcherExecuted",true)
                .put("nativeAppClientsChanged",false).put("nativeWindowsChanged",false).put("capturedPixels",false)
                .put("result",initiallyQueued&&dispatched&&delivered&&noReplay&&independentOther?"passed":"failed");
        System.out.println(result);
        if (!initiallyQueued||!dispatched||!delivered||!noReplay||!independentOther) throw new IllegalStateException("Native queue fixture failed");
        System.exit(0);
    }
}
