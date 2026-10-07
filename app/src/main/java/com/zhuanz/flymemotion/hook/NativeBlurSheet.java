package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import com.zhuanz.flymemotion.core.MotionFrame;
import java.lang.reflect.Method;

/** Let the OEM Drawable track RenderNode positions/crop and aggregate its own blur ABI. */
final class NativeBlurSheet {
    private final Method create, radius, crop, blends, invalidate, corners;
    private int cornerPx;
    private ViewGroup window;
    private View sheet;
    private Drawable drawable;
    private boolean clipChildren, clipPadding;
    /** Null when the blur Drawable can be cropped to the sheet on this build; otherwise no sheet blur. */
    final String missing;
    NativeBlurSheet(ClassLoader loader) throws ClassNotFoundException {
        Class<?> root = Class.forName("android.view.ViewRootImpl", false, loader);
        Class<?> type = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable", false, loader);
        create = NativeReflection.methodOrNull(root, "createBackgroundBlurDrawable");
        // Per-corner since Android 12. Without it the blur stays square rather than rounding its top.
        corners = NativeReflection.methodOrNull(type, "setCornerRadius", float.class, float.class, float.class, float.class);
        radius = NativeReflection.methodOrNull(type, "setBlurRadius", int.class);
        // setCropRect/setBlendModes are MIUI additions to the AOSP Drawable.
        crop = NativeReflection.methodOrNull(type, "setCropRect", boolean.class, Rect.class);
        blends = NativeReflection.methodOrNull(type, "setBlendModes", int[][].class);
        invalidate = NativeReflection.methodOrNull(type, "invalidateBlurRegion");
        String miss = create == null ? "ViewRootImpl#createBackgroundBlurDrawable" : radius == null ? "setBlurRadius"
                : crop == null ? "setCropRect" : invalidate == null ? "invalidateBlurRegion" : null;
        missing = miss == null ? null : miss + " missing";
    }
    void begin(View windowView, Object root) {
        if (missing != null) return;
        if (!(windowView instanceof ViewGroup group)) throw new IllegalStateException("Shade root is not a ViewGroup");
        window = group;
        clipChildren = group.getClipChildren(); clipPadding = group.getClipToPadding();
        group.setClipChildren(false); group.setClipToPadding(false);
        drawable = (Drawable) NativeReflection.invoke(create, root);
        NativeReflection.invoke(radius, drawable, 0);
        // Same bottom corners as the wallpaper sheet above it; the top edge stays square.
        if (corners != null) NativeReflection.invoke(corners, drawable, 0f, 0f, (float) cornerPx, (float) cornerPx);
        sheet = new View(group.getContext());
        sheet.setTag("FlymeMotion-NativeBlurSheet");
        sheet.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.setClickable(false); sheet.setFocusable(false);
        sheet.setBackground(drawable);
        // Draw before the original controls: the native Drawable's transparent SRC must never
        // erase clock/notifications. It is not a new window or an input target.
        group.addView(sheet, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    boolean cornersSupported() { return corners != null; }
    void corner(int px) {
        cornerPx = px;
        if (drawable != null && corners != null) NativeReflection.invoke(corners, drawable, 0f, 0f, (float) px, (float) px);
    }
    void apply(MotionFrame frame, int blurRadius, int color) {
        applyRegion(frame.blurY(),frame.blurHeight(),blurRadius,color);
    }
    void applyRegion(float screenY,float height,int blurRadius,int color) {
        if (sheet == null) return;
        // Root motion must not shift the native blur sheet a second time.
        sheet.setTranslationY(screenY - window.getTranslationY());
        NativeReflection.invoke(radius, drawable, blurRadius);
        if (blends != null) NativeReflection.invoke(blends, drawable, (Object) (blurRadius == 0 ? new int[0][] : new int[][]{{9, color}}));
        NativeReflection.invoke(crop, drawable, true, new Rect(0, 0, window.getWidth(), Math.max(0, Math.round(height))));
        drawable.setVisible(blurRadius > 0 && height > 0, false);
        NativeReflection.invoke(invalidate, drawable);
    }
    void release() {
        // Remove blur immediately on AOD/auth/unlock handoff. Only mirror coverage may await
        // the source-restored receipt; a retired blur must not freeze over the next scene.
        if (drawable != null) { NativeReflection.invoke(radius, drawable, 0); drawable.setVisible(false, false); }
        if (sheet != null) { sheet.setBackground(null); if (sheet.getParent() instanceof ViewGroup parent) parent.removeView(sheet); }
        if (window != null) {
            if (!window.getClipChildren()) window.setClipChildren(clipChildren);
            if (!window.getClipToPadding()) window.setClipToPadding(clipPadding);
        }
        drawable = null; sheet = null; window = null;
    }
}
