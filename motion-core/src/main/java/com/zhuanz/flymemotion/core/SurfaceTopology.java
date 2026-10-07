package com.zhuanz.flymemotion.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Committed server topology. Eviction must retain the complete ancestry of every live surface. */
public final class SurfaceTopology {
    private final Map<Integer, Boolean> visible = new LinkedHashMap<>();
    private final Map<Integer, Integer> parents = new LinkedHashMap<>();
    public void created(int layer, int parent, boolean shown) { visible.put(layer, shown); parents.put(layer, parent); }
    public void visibility(int layer, boolean shown) { visible.put(layer, shown); }
    public void reparent(int layer, int parent) { parents.put(layer, parent); }
    public Boolean visibility(int layer) { return visible.get(layer); }
    public Integer parent(int layer) { return parents.get(layer); }
    public int size() { return visible.size(); }
    public Set<Integer> retainLiveChains(Set<Integer> live) {
        Set<Integer> keep = new HashSet<>(live);
        for (int layer : live) {
            Set<Integer> visited = new HashSet<>();
            int current = layer;
            while (current != 0 && visited.add(current)) {
                keep.add(current);
                Integer parent = parents.get(current);
                if (parent == null) break;
                current = parent;
            }
        }
        visible.keySet().removeIf(layer -> !keep.contains(layer));
        parents.keySet().removeIf(layer -> !keep.contains(layer));
        return keep;
    }
    public String blocker(int layer) {
        Set<Integer> visited = new HashSet<>();
        int current = layer;
        while (current != 0) {
            if (!visited.add(current)) return "cycle:" + current;
            Boolean shown = visible.get(current);
            if (shown == null) return "unknown-visibility:" + current;
            if (!shown) return "hidden:" + current;
            Integer parent = parents.get(current);
            if (parent == null) return "unknown-parent:" + current;
            current = parent;
        }
        return "none";
    }
}
