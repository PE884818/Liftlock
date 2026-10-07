package com.zhuanz.flymemotion;

/** Public action names carry only lifecycle and bounded rendering receipts, never application pixels. */
public final class SceneProtocol {
    public static final String REQUEST = "com.zhuanz.flymemotion.NATIVE_SCENE";
    public static final String STATUS = REQUEST + "_STATUS";
    public static final String WALLPAPER_STATUS = REQUEST + "_WALLPAPER_STATUS";
    public static final String REVOKE = REQUEST + "_REVOKE";
    public static final String CLIENT_FRAME = REQUEST + "_CLIENT_FRAME";
    public static final String CLIENT_FRAME_STATUS = CLIENT_FRAME + "_STATUS";
    public static final String WALLPAPER = "com.miui.miwallpaper";
    public static final String MANAGER = "com.zhuanz.flymemotion";
    private SceneProtocol() {}
}
