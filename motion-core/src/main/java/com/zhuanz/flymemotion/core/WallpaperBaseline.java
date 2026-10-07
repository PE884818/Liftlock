package com.zhuanz.flymemotion.core;

/** Native ViewRoot cleanup removes its reference, but does not reset the wallpaper leash itself. */
public final class WallpaperBaseline {
    private long layer = -1;
    private float[] matrix;
    public void observe(long surfaceLayer, float[] nativeMatrix) {
        if (surfaceLayer < 0 || nativeMatrix == null) return;
        matrix = copy(nativeMatrix); layer = surfaceLayer;
    }
    public float[] snapshot(long wallpaperLayer, long rootLayer, float[] rootMatrix) {
        float[] result = snapshotOrNull(wallpaperLayer, rootLayer, rootMatrix);
        if (result != null) return result;
        throw new IllegalStateException("Native wallpaper baseline not observed; retaining native coverage");
    }
    public float[] snapshotOrNull(long wallpaperLayer, long rootLayer, float[] rootMatrix) {
        if (wallpaperLayer >= 0 && wallpaperLayer == rootLayer && rootMatrix != null) return copy(rootMatrix);
        if (wallpaperLayer >= 0 && wallpaperLayer == layer && matrix != null) return matrix.clone();
        return null;
    }
    /** WM geometry is a current fallback, not a permanently observed SystemUI wake transform. */
    public float[] snapshotOrNull(long wallpaperLayer, long rootLayer, float[] rootMatrix, long wmLayer, float[] wmMatrix) {
        float[] nativeValue = snapshotOrNull(wallpaperLayer, rootLayer, rootMatrix);
        if (nativeValue != null) return nativeValue;
        return wallpaperLayer >= 0 && wallpaperLayer == wmLayer && wmMatrix != null ? copy(wmMatrix) : null;
    }
    private static float[] copy(float[] value) {
        if (value.length != 5) throw new IllegalArgumentException("Native wallpaper matrix length");
        for (float component : value) if (!Float.isFinite(component)) throw new IllegalArgumentException("Invalid wallpaper matrix");
        if (value[0] <= 0 || value[1] <= 0) throw new IllegalArgumentException("Invalid wallpaper scale");
        return value.clone();
    }
}
