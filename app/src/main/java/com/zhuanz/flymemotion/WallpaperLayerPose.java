package com.zhuanz.flymemotion;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.zhuanz.flymemotion.core.MotionFrame;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Wallpaper roles remain in their original tree/Z order, separate from the moving controls. */
public final class WallpaperLayerPose {
    private record State(View view, float y, float ownedY, float alpha, float ownedAlpha, Rect clip, Rect ownedClip) {}
    private record ParentClip(ViewGroup view, boolean children, boolean padding) {}
    private final List<State> states = new ArrayList<>();
    private final List<ParentClip> parents = new ArrayList<>();
    public int apply(MotionFrame frame, LockscreenPose content, View... roles) {
        List<State> next = new ArrayList<>();
        LinkedHashSet<View> roots = new LinkedHashSet<>();
        for (View role : roles) if (role != null && role.getVisibility() == View.VISIBLE) roots.add(role);
        LinkedHashSet<ViewGroup> unclipped = new LinkedHashSet<>();
        for (View role : roots) {
            boolean inherited = false;
            for (ViewParent p = role.getParent(); p instanceof View; p = p.getParent())
                if (roots.contains(p)) { inherited = true; break; }
            if (inherited) continue;
            float currentY = role.getTranslationY(), y = currentY, alpha = role.getAlpha();
            Rect clip = role.getClipBounds(); clip = clip == null ? null : new Rect(clip);
            for (State previous : states) if (previous.view == role) {
                if (Math.abs(currentY - previous.ownedY) < .001f) y = previous.y;
                if (Math.abs(alpha - previous.ownedAlpha) < .001f) alpha = previous.alpha;
                if (previous.ownedClip.equals(clip)) clip = previous.clip;
                break;
            }
            float ownedY = y - content.inheritedOffset(role), ownedAlpha = alpha * frame.wallpaperAlpha();
            if (Math.abs(currentY - ownedY) >= .001f) role.setTranslationY(ownedY);
            Matrix global = new Matrix(), inverse = new Matrix(); role.transformMatrixToGlobal(global);
            if (!global.invert(inverse)) { role.setTranslationY(currentY); continue; }
            RectF visible = new RectF(0, 0, role.getRootView().getWidth(), frame.wallpaperHeight());
            inverse.mapRect(visible);
            Rect ownedClip = new Rect(); visible.roundOut(ownedClip);
            if (!ownedClip.intersect(0, 0, role.getWidth(), role.getHeight())) ownedClip.setEmpty();
            if (clip != null && !ownedClip.intersect(clip)) ownedClip.setEmpty();
            next.add(new State(role, y, ownedY, alpha, ownedAlpha, clip, new Rect(ownedClip)));
            if (Math.abs(role.getAlpha() - ownedAlpha) >= .001f) role.setAlpha(ownedAlpha);
            if (!ownedClip.equals(role.getClipBounds())) role.setClipBounds(ownedClip);
            // A translated ancestor's local clip cannot define this stationary wallpaper's
            // viewport. This role's explicit screen-space crop does; restore native flags later.
            for (ViewParent p = role.getParent(); p instanceof ViewGroup group; p = p.getParent()) {
                if (unclipped.add(group)) {
                    ParentClip previous = null;
                    for (ParentClip saved : parents) if (saved.view == group) { previous = saved; break; }
                    if (previous == null) parents.add(new ParentClip(group, group.getClipChildren(), group.getClipToPadding()));
                    else if (group.getClipChildren() || group.getClipToPadding()) {
                        parents.remove(previous);
                        parents.add(new ParentClip(group, group.getClipChildren() || previous.children,
                                group.getClipToPadding() || previous.padding));
                    }
                    if (group.getClipChildren()) group.setClipChildren(false);
                    if (group.getClipToPadding()) group.setClipToPadding(false);
                }
            }
        }
        for (State previous : states)
            if (next.stream().noneMatch(s -> s.view == previous.view)) restore(previous);
        states.clear(); states.addAll(next);
        if (states.isEmpty()) restoreParents();
        return states.size();
    }
    public void restore() {
        for (State s : states) restore(s);
        states.clear();
        restoreParents();
    }
    private static void restore(State s) {
        if (Math.abs(s.view.getTranslationY() - s.ownedY) < .001f && Math.abs(s.y - s.ownedY) >= .001f) s.view.setTranslationY(s.y);
        if (Math.abs(s.view.getAlpha() - s.ownedAlpha) < .001f && Math.abs(s.alpha - s.ownedAlpha) >= .001f) s.view.setAlpha(s.alpha);
        if (s.ownedClip.equals(s.view.getClipBounds()) && !s.ownedClip.equals(s.clip)) s.view.setClipBounds(s.clip);
    }
    private void restoreParents() {
        for (ParentClip p : parents) {
            if (!p.view.getClipChildren()) p.view.setClipChildren(p.children);
            if (!p.view.getClipToPadding()) p.view.setClipToPadding(p.padding);
        }
        parents.clear();
    }
}
