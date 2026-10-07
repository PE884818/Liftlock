package com.zhuanz.flymemotion.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * Installs one optional part of an adapter. If the part cannot bind on this build, only the hooks
 * it registered are undone and the reason is logged; the rest of the adapter keeps installing.
 */
public final class FeatureGate {
    private FeatureGate() {}
    public interface Block { void run() throws Throwable; }

    public static <H> boolean optional(CompatLog log, String feature, List<H> handles, Consumer<H> undo, Block block) {
        int mark = handles.size();
        try {
            block.run();
            log.on(feature, "");
            return true;
        } catch (Throwable t) {
            for (int i = handles.size() - 1; i >= mark; i--) {
                H handle = handles.remove(i);
                try { undo.accept(handle); } catch (Throwable ignored) {}
            }
            log.off(feature, reason(t));
            return false;
        }
    }

    public static String reason(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
