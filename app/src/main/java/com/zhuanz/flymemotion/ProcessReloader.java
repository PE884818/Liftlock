package com.zhuanz.flymemotion;

import android.content.Context;
import android.content.Intent;
import android.app.BroadcastOptions;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.zhuanz.flymemotion.core.ReloadPlan;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Commands run only after a manager button is pressed. Never enables an LSPosed scope. */
public final class ProcessReloader {
    private final Context context;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean busy;
    private volatile String message = "重载需要本应用的 Root 权限";
    ProcessReloader(Context context) { this.context = context.getApplicationContext(); }
    public boolean busy() { return busy; }
    public String message() { return message; }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    public synchronized void reload(String pkg) {
        ReloadPlan.requirePackage(pkg);
        if (busy) return;
        busy = true; message = "正在重载 " + label(pkg) + "…"; publish();
        worker.execute(() -> {
            try {
                ConfigurationStore store = MotionApplication.configuration();
                if (store.pending()) { message = "配置尚未同步到框架，请等待连接后再重载。"; return; }
                CommandResult permission = root("id -u");
                if (permission.code != 0 || !permission.output.strip().equals("0"))
                    throw new IllegalStateException("Root 权限未获准");
                revokeScenes();
                CommandResult lookup = root("pidof " + pkg);
                if (lookup.code != 0 && lookup.output.isBlank()) {
                    message = label(pkg) + " 当前未运行；下次打开时加载模块。"; return;
                }
                if (lookup.code != 0) throw new IllegalStateException(lookup.output.strip());
                List<Integer> pids = ReloadPlan.pids(lookup.output);
                if (pids.isEmpty()) { message = label(pkg) + " 当前未运行；下次打开时加载模块。"; return; }
                for (int pid : pids) {
                    CommandResult identity = root("cat /proc/" + pid + "/cmdline");
                    if (identity.code != 0 || !ReloadPlan.belongsTo(pkg, identity.output))
                        throw new IllegalStateException("进程标识已变化，请重试");
                }
                Bundle invalidation = new Bundle();
                invalidation.putString("pids", pids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" ")));
                context.getContentResolver().call(RuntimeProvider.URI, "invalidate", pkg, invalidation);
                CommandResult signal = root(ReloadPlan.killCommand(pids));
                if (signal.code != 0) throw new IllegalStateException(signal.output.strip());
                message = pkg.equals(ReloadPlan.SYSTEMUI) ? "SystemUI 已收到重载请求，等待新的 Hook 回执。"
                        : pkg.equals(ReloadPlan.WALLPAPER) ? "壁纸进程已收到重载请求，等待原生重新绑定和新版回执。"
                        : "系统设置已收到重载请求，重新打开锁屏设置页检查入口。";
            } catch (Exception e) {
                String detail = String.valueOf(e.getMessage()).replace('\0', ' ');
                message = "重载失败：" + detail.substring(0, Math.min(180, detail.length()))
                        + "\n请在 KernelSU / Root 管理器中授予「Liftlock」Root 权限后重试。";
            } finally { busy = false; publish(); }
        });
    }
    private CommandResult root(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IllegalStateException("Root 请求超时");
        }
        byte[] output = process.getInputStream().readAllBytes();
        if (output.length > 16_384) throw new IllegalStateException("异常命令输出");
        return new CommandResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
    private void revokeScenes() throws Exception {
        Bundle before = context.getContentResolver().call(RuntimeProvider.URI, "read", null, null);
        var required = new java.util.ArrayList<String>();
        long nonce = android.os.SystemClock.elapsedRealtimeNanos();
        for (String pkg : new String[]{ReloadPlan.SYSTEMUI, "android", ReloadPlan.WALLPAPER}) {
            String version = before == null ? "" : before.getString(pkg + ".moduleVersion", "");
            // Revoke already loaded peers before replacing a process, including an older framework.
            if (before != null && (TargetBuild.compatibleRuntime(pkg, version)
                    || "0.2.5-stage1".equals(version) || "0.2.6-stage1".equals(version) || "0.2.7-stage1".equals(version) || "0.2.8-stage1".equals(version) || "0.2.9-stage1".equals(version) || "0.2.10-stage1".equals(version) || "0.2.11-stage1".equals(version) || "0.2.12-stage1".equals(version) || "0.2.13-stage1".equals(version) || "0.2.14-stage1".equals(version) || "0.2.15-stage1".equals(version))
                    && "loaded".equals(before.getString(pkg + ".state"))) required.add(pkg);
            context.sendBroadcast(new Intent(SceneProtocol.REVOKE).setPackage(pkg).putExtra("nonce", nonce), null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        }
        long deadline = android.os.SystemClock.uptimeMillis() + 3000;
        while (!required.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
            Bundle receipt = context.getContentResolver().call(RuntimeProvider.URI, "read", null, null);
            if (receipt != null) required.removeIf(pkg -> "-1".equals(receipt.getString(pkg + ".sceneSession"))
                    && Long.toString(nonce).equals(receipt.getString(pkg + ".revokeNonce")));
            if (!required.isEmpty()) Thread.sleep(50);
        }
        if (!required.isEmpty()) throw new IllegalStateException("活动场景未确认回滚：" + String.join(",", required));
    }
    private static String label(String pkg) { return pkg.equals(ReloadPlan.SYSTEMUI) ? "SystemUI" : pkg.equals(ReloadPlan.WALLPAPER) ? "壁纸进程" : "系统设置"; }
    private void publish() { for (Runnable listener : listeners) main.post(listener); }
    private record CommandResult(int code, String output) {}
}
