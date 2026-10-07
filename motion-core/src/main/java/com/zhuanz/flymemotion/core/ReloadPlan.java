package com.zhuanz.flymemotion.core;

import java.util.LinkedHashSet;
import java.util.List;

/** Only the two module scopes can be reloaded. No arbitrary shell input. */
public final class ReloadPlan {
    public static final String SYSTEMUI = "com.android.systemui", SETTINGS = "com.android.settings", WALLPAPER = "com.miui.miwallpaper";
    private ReloadPlan() {}
    public static String requirePackage(String pkg) {
        if (!SYSTEMUI.equals(pkg) && !SETTINGS.equals(pkg) && !WALLPAPER.equals(pkg)) throw new IllegalArgumentException("Unscoped reload target");
        return pkg;
    }
    public static List<Integer> pids(String output) {
        if (output == null || output.isBlank()) return List.of();
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (String token : output.strip().split("\\s+")) {
            if (!token.matches("[0-9]{1,8}")) throw new IllegalArgumentException("Invalid PID response");
            int pid = Integer.parseInt(token);
            if (pid <= 1) throw new IllegalArgumentException("Invalid PID response");
            values.add(pid);
        }
        if (values.size() > 16) throw new IllegalArgumentException("Unexpected process count");
        return List.copyOf(values);
    }
    public static String killCommand(List<Integer> pids) {
        if (pids.isEmpty() || pids.size() > 16) throw new IllegalArgumentException("No bounded target PID list");
        StringBuilder command = new StringBuilder("kill -TERM");
        for (Integer pid : pids) {
            if (pid == null || pid <= 1 || pid > 99_999_999) throw new IllegalArgumentException("Invalid target PID");
            command.append(' ').append(pid);
        }
        return command.toString();
    }
    public static boolean belongsTo(String pkg, String cmdline) {
        requirePackage(pkg);
        if (cmdline == null) return false;
        int nul = cmdline.indexOf('\0');
        return pkg.equals(nul < 0 ? cmdline : cmdline.substring(0, nul));
    }
}
