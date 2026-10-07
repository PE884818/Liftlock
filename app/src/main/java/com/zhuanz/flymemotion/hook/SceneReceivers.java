package com.zhuanz.flymemotion.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import com.zhuanz.flymemotion.SceneProtocol;

final class SceneReceivers {
    static int uid(Context context, String pkg) {
        try { return context.getPackageManager().getPackageUid(pkg, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return -1; }
    }
    static boolean fromPackage(Context context, BroadcastReceiver receiver, String pkg) {
        int expected = uid(context, pkg);
        return expected >= 0 && pkg.equals(receiver.getSentFromPackage()) && receiver.getSentFromUid() == expected;
    }
    static void revoke(Context context, Handler handler, RuntimeReporter reporter, Runnable rollback) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent intent) {
                if (fromPackage(c, this, SceneProtocol.MANAGER)) {
                    rollback.run(); reporter.revokeNonce = intent.getLongExtra("nonce", -1);
                    reporter.sceneSession = -1; reporter.publish();
                }
            }
        }, new IntentFilter(SceneProtocol.REVOKE), null, handler, Context.RECEIVER_EXPORTED);
    }
}
