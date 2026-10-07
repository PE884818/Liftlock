package com.zhuanz.flymemotion;

import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Moves complete native visual roots. Child layers inherit once; no progress rectangle is applied. */
public final class LockscreenPose {
    private record Translation(View view, float original, float owned) {}
    private final List<Translation> translations = new ArrayList<>(3);

    public boolean apply(float offset, View... layers) {
        var roots = new LinkedHashSet<View>();
        for (View view : layers) if (view != null && view.getVisibility() == View.VISIBLE) roots.add(view);
        var next = new ArrayList<Translation>(3);
        for (View view : roots) {
            boolean inherited = false;
            for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
                if (roots.contains(parent)) { inherited = true; break; }
            if (inherited) continue;
            float current = view.getTranslationY(), original = current;
            for (Translation previous : translations) if (previous.view == view) {
                if (Math.abs(current - previous.owned) < .001f) original = previous.original;
                break;
            }
            float owned = original + offset;
            next.add(new Translation(view, original, owned));
            // Keep the presented RenderNode continuously owned. Restoring the baseline here
            // creates an intermediate native pose on every pre-draw/height callback.
            if (Math.abs(current - owned) >= .001f) view.setTranslationY(owned);
        }
        for (Translation previous : translations)
            if (next.stream().noneMatch(t -> t.view == previous.view)) restore(previous);
        translations.clear(); translations.addAll(next);
        return !translations.isEmpty() && Math.abs(offset) > .001f;
    }
    public void restore() {
        for (Translation t : translations) restore(t);
        translations.clear();
    }
    private static void restore(Translation t) {
        if (Math.abs(t.view.getTranslationY() - t.owned) < .001f
                && Math.abs(t.owned - t.original) >= .001f) t.view.setTranslationY(t.original);
    }
    /** The module-owned ancestor offset only; native translations and elevations are untouched. */
    public float inheritedOffset(View view) {
        float offset = 0;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            for (Translation t : translations) if (t.view == parent) offset += t.owned - t.original;
        return offset;
    }
}
