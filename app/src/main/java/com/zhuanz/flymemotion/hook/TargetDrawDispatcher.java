package com.zhuanz.flymemotion.hook;

import java.lang.reflect.Method;
import android.os.IInterface;
import android.util.ArrayMap;

/** Dispatch only the prepared return client's existing native lifecycle transaction. */
final class TargetDrawDispatcher {
    private final Method dispatch, dispatchAll;
    TargetDrawDispatcher(ClassLoader loader) throws ClassNotFoundException {
        Class<?> lifecycle = Class.forName("com.android.server.wm.ClientLifecycleManager",false,loader);
        // One client's batch (Android 17), else every pending batch (Android 15), else nothing:
        // without batching (Android 14) reportResized already went out directly.
        dispatch = NativeReflection.methodOrNull(lifecycle,"dispatchPendingTransaction",Class.forName("android.app.IApplicationThread",false,loader));
        dispatchAll = NativeReflection.methodOrNull(lifecycle,"dispatchPendingTransactions");
    }
    String mode() { return dispatch != null ? "per-client" : dispatchAll != null ? "all-pending" : "direct"; }
    boolean dispatch(Object lifecycle, Object client) {
        if (client == null || lifecycle == null) return false;
        if (dispatch == null) {
            if (dispatchAll == null) return false;
            NativeReflection.invoke(dispatchAll, lifecycle); return true;
        }
        if (NativeReflection.optGet(lifecycle,"mPendingTransactions") instanceof ArrayMap<?,?> pending
                && !pending.containsKey(((IInterface)client).asBinder())) return false;
        NativeReflection.invoke(dispatch,lifecycle,client); return true;
    }
}
