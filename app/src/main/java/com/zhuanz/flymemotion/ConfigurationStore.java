package com.zhuanz.flymemotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.zhuanz.flymemotion.core.MotionConfig;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConfigurationStore {
    private final SharedPreferences local;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile MotionConfig config;
    private volatile XposedService service;
    private volatile SharedPreferences remote;
    private volatile String framework = "等待 LSPosed 启用", problem = "";
    private volatile List<String> scope = List.of();
    private volatile boolean pending;
    private final SharedPreferences.OnSharedPreferenceChangeListener remoteListener = this::onRemoteChanged;
    private void onRemoteChanged(SharedPreferences p, String key) {
        if (key == null || ConfigCodec.KEY.equals(key)) worker.execute(() -> {
            try {
                MotionConfig received = ConfigCodec.decode(p.getString(ConfigCodec.KEY, null));
                synchronized (this) {
                    if (p != remote || pending || received.revision <= config.revision) return;
                    config = received;
                }
                local.edit().putString(ConfigCodec.KEY, ConfigCodec.encode(received)).putBoolean("pending", false).commit();
                notifyChanged();
            } catch (RuntimeException e) { failure(e); }
        });
    }

    public ConfigurationStore(Context context) {
        local = context.getSharedPreferences("configuration", Context.MODE_PRIVATE);
        config = ConfigCodec.decode(local.getString(ConfigCodec.KEY, null));
        pending = local.getBoolean("pending", false);
    }
    public void connect() {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService s) { worker.execute(() -> bind(s)); }
            @Override public void onServiceDied(XposedService s) {
                worker.execute(() -> {
                    if (service != s) return;
                    if (remote != null) remote.unregisterOnSharedPreferenceChangeListener(remoteListener);
                    service = null; remote = null; scope = List.of(); framework = "LSPosed 连接已断开";
                    notifyChanged();
                });
            }
        });
    }
    private void bind(XposedService s) {
        try {
            if (s.getApiVersion() < 101 || (s.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0)
                throw new IllegalStateException("框架需要 API 101 和 Remote Preferences 支持");
            // The tester owns the LSPosed scope; never remove packages from it.
            if (remote != null) remote.unregisterOnSharedPreferenceChangeListener(remoteListener);
            SharedPreferences p = s.getRemotePreferences(ConfigCodec.GROUP);
            // The app's device-protected mirror also preserves an emergency stop while the service was absent.
            MotionConfig selected;
            boolean write;
            synchronized (this) {
                MotionConfig received = ConfigCodec.decode(p.getString(ConfigCodec.KEY, null));
                write = pending || !p.contains(ConfigCodec.KEY) || config.revision > received.revision;
                selected = write ? config : received;
                config = selected;
            }
            if (write) {
                if (!p.edit().putString(ConfigCodec.KEY, ConfigCodec.encode(selected)).commit())
                    throw new IllegalStateException("远程配置写入失败");
            }
            synchronized (this) { if (config.revision == selected.revision) pending = false; }
            local.edit().putString(ConfigCodec.KEY, ConfigCodec.encode(selected)).putBoolean("pending", pending).commit();
            remote = p; service = s; scope = List.copyOf(s.getScope());
            framework = s.getFrameworkName() + " " + s.getFrameworkVersion() + " · API " + s.getApiVersion();
            problem = ""; p.registerOnSharedPreferenceChangeListener(remoteListener); notifyChanged();
        } catch (RuntimeException e) { failure(e); }
    }
    public MotionConfig get() { return config; }
    public String framework() { return framework; }
    public String problem() { return problem; }
    public List<String> scope() { return scope; }
    public boolean connected() { return remote != null; }
    public boolean pending() { return pending; }
    public void refreshScope() {
        worker.execute(() -> {
            XposedService s = service;
            if (s == null) return;
            try { scope = List.copyOf(s.getScope()); notifyChanged(); }
            catch (RuntimeException e) { failure(e); }
        });
    }
    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    public void save(MotionConfig next) {
        synchronized (this) { config = next; pending = true; }
        // Persist the user's choice before notifying the framework.
        worker.execute(() -> {
            if (!local.edit().putString(ConfigCodec.KEY, ConfigCodec.encode(next)).putBoolean("pending", true).commit()) {
                problem = "本地配置写入失败"; notifyChanged(); return;
            }
            SharedPreferences p = remote;
            if (p != null) try {
                if (!p.edit().putString(ConfigCodec.KEY, ConfigCodec.encode(next)).commit())
                    throw new IllegalStateException("远程配置写入失败");
                synchronized (this) { if (config.revision == next.revision) pending = false; }
                local.edit().putBoolean("pending", pending).commit();
                problem = "";
            } catch (RuntimeException e) { failure(e); }
            notifyChanged();
        });
        notifyChanged();
    }
    private void failure(RuntimeException e) { problem = e.getMessage(); notifyChanged(); }
    private void notifyChanged() { for (Runnable r : listeners) main.post(r); }
}
