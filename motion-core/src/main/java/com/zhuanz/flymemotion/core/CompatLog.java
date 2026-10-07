package com.zhuanz.flymemotion.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which parts of a process's adapter are live on this system build, and through which member
 * variant. Published with the receipt so a tester can send it back. Encoded one entry per line:
 * {@code feature|on|detail} or {@code feature|off|reason}.
 */
public final class CompatLog {
    public record Entry(String feature, boolean on, String detail) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void on(String feature, String detail) {
        Entry old = entries.get(feature);
        // Off is sticky: a later partial success of the same feature must not hide the miss.
        if (old == null || old.on()) entries.put(feature, new Entry(feature, true, merge(old, detail)));
    }
    public synchronized void off(String feature, String reason) {
        entries.put(feature, new Entry(feature, false, clean(reason)));
    }
    /** Adds a variant note to a feature without changing its state. */
    public synchronized void note(String feature, String detail) {
        Entry old = entries.get(feature);
        entries.put(feature, new Entry(feature, old == null || old.on(), merge(old, detail)));
    }
    public synchronized boolean anyOff() {
        for (Entry e : entries.values()) if (!e.on()) return true;
        return false;
    }
    public synchronized String encode() {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries.values())
            out.append(clean(e.feature())).append('|').append(e.on() ? "on" : "off").append('|').append(e.detail()).append('\n');
        return out.toString();
    }
    public static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] p = line.split("[|]", 3);
            if (p.length >= 2 && !p[0].isEmpty()) out.add(new Entry(p[0], "on".equals(p[1]), p.length > 2 ? p[2] : ""));
        }
        return out;
    }
    private static String merge(Entry old, String detail) {
        String d = clean(detail);
        if (old == null || old.detail().isEmpty()) return d;
        if (d.isEmpty() || old.detail().contains(d)) return old.detail();
        return old.detail() + "; " + d;
    }
    private static String clean(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
