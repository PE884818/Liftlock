package com.zhuanz.flymemotion;

import android.app.Application;

public final class MotionApplication extends Application {
    private static ConfigurationStore store;
    private static ProcessReloader reloader;
    @Override public void onCreate() {
        super.onCreate();
        store = new ConfigurationStore(createDeviceProtectedStorageContext());
        reloader = new ProcessReloader(this);
        store.connect();
    }
    public static ConfigurationStore configuration() { return store; }
    public static ProcessReloader reloader() { return reloader; }
}
