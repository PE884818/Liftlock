package com.zhuanz.flymemotion;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.view.Display;
import android.view.RoundedCorner;
import android.view.View;
import com.zhuanz.flymemotion.core.MotionConfig;

/**
 * The lifted sheet's bottom corner radius in pixels. Shared by the SystemUI hook and the manager,
 * so the slider's "跟随屏幕" point is the same number the sheet uses. Only API presence decides
 * which source is read; there is no device or version check.
 */
public final class PanelCorner {
    private PanelCorner() {}

    /** [MotionConfig#FOLLOW_SCREEN] or a negative value reads the panel; otherwise dp → px. */
    public static float radius(View view, float overrideDp) {
        if (overrideDp >= 0) return overrideDp * view.getResources().getDisplayMetrics().density;
        return screen(view.getDisplay());
    }

    public static float screenDp(View view) {
        return screen(view.getDisplay()) / view.getResources().getDisplayMetrics().density;
    }

    // getIdentifier: the framework dimen is only the fallback when the display reports nothing.
    @SuppressLint("DiscouragedApi")
    static float screen(Display display) {
        float reported = 0;
        if (display != null) try {
            for (int position : new int[]{RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT}) {
                RoundedCorner corner = display.getRoundedCorner(position);
                if (corner != null) reported = Math.max(reported, corner.getRadius());
            }
        } catch (NoClassDefFoundError | NoSuchMethodError older) {
            // A ROM that stripped the display corner API still has the framework dimen below.
        }
        if (reported > 0) return reported;
        Resources system = Resources.getSystem();
        for (String name : new String[]{"rounded_corner_radius_bottom", "rounded_corner_radius"}) {
            int id = system.getIdentifier(name, "dimen", "android");
            if (id != 0 && system.getDimensionPixelSize(id) > 0) return system.getDimensionPixelSize(id);
        }
        return 0;
    }
}
