package com.zhuanz.flymemotion.hook;

import com.zhuanz.flymemotion.core.CompatLog;
import com.zhuanz.flymemotion.core.FeatureGate;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.List;

/** Core/optional install blocks for one process; see FeatureGate. */
final class Features {
    private final MotionModule module;
    private final List<XposedInterface.HookHandle> handles;
    final CompatLog log;
    Features(MotionModule module, List<XposedInterface.HookHandle> handles, CompatLog log) {
        this.module = module; this.handles = handles; this.log = log;
    }
    boolean optional(String feature, FeatureGate.Block block) {
        return FeatureGate.optional(log, feature, handles, XposedInterface.HookHandle::unhook, block);
    }
    /**
     * Deoptimization keeps ART from inlining a hooked callee into its caller. A build that refuses
     * it still runs the hook wherever it is not inlined, so this degrades instead of failing.
     */
    void deopt(String feature, Executable method) {
        if (method == null) return;
        boolean ok;
        try { ok = module.deoptimize(method); } catch (Throwable t) { ok = false; }
        if (!ok) log.note(feature, "deopt " + method.getName() + " refused");
    }
    /** Records which candidate a core lookup used, when it was not the first. */
    void variant(String feature, String detail) { log.note(feature, detail); }
}
