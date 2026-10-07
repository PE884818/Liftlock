package com.zhuanz.flymemotion;

import com.zhuanz.flymemotion.core.MotionConfig;
import com.zhuanz.flymemotion.core.BottomGestureRegion;
import org.json.JSONObject;

public final class ConfigCodec {
    public static final String GROUP = "motion", KEY = "snapshot";
    private ConfigCodec() {}
    public static String encode(MotionConfig c) {
        try {
            return new JSONObject().put("schema", 1).put("enabled", c.enabled)
                    .put("lateDesktopAnimation", c.lateDesktopAnimation).put("stiffness", c.stiffness)
                    .put("expandDamping", c.expandDamping).put("closeDamping", c.closeDamping)
                    .put("minimumVelocityDp", c.minimumVelocityDp).put("threshold", c.threshold)
                    .put("blurStrength", c.blurStrength).put("revision", c.revision)
                    .put("bottomEnabled", c.bottomRegion.enabled()).put("bottomHeight", c.bottomRegion.height())
                    .put("bottomWidth", c.bottomRegion.width()).put("bottomCenter", c.bottomRegion.center())
                    .put("cornerRadiusDp", c.cornerRadiusDp).toString();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid motion configuration", e); }
    }
    public static MotionConfig decode(String value) {
        if (value == null || value.isBlank()) return MotionConfig.defaults();
        try {
            JSONObject j = new JSONObject(value);
            if (j.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported configuration schema");
            return new MotionConfig(j.getBoolean("enabled"), j.optBoolean("lateDesktopAnimation", false),
                    (float) j.getDouble("stiffness"), (float) j.getDouble("expandDamping"),
                    (float) j.getDouble("closeDamping"), (float) j.getDouble("minimumVelocityDp"),
                    (float) j.getDouble("threshold"), (float) j.getDouble("blurStrength"), j.getLong("revision"),
                    new BottomGestureRegion(j.optBoolean("bottomEnabled", true), (float) j.optDouble("bottomHeight", .15),
                            (float) j.optDouble("bottomWidth", 1), (float) j.optDouble("bottomCenter", .5)),
                    (float) j.optDouble("cornerRadiusDp", MotionConfig.FOLLOW_SCREEN));
        } catch (Exception e) {
            // Damaged or future preferences must not activate a system hook.
            return MotionConfig.defaults().withEnabled(false);
        }
    }
}
