package com.zhuanz.flymemotion.core;

/** A launcher overlay may also claim BASE_APPLICATION; its owner and alpha must match the return app. */
public final class ReturnWindowPolicy {
    private ReturnWindowPolicy() {}
    public static boolean main(int type, int ownerUid, int activityUid, float alpha) {
        return type == 1 && ownerUid == activityUid && Float.isFinite(alpha) && alpha > 0;
    }
}
