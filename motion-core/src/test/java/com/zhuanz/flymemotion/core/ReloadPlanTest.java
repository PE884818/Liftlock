package com.zhuanz.flymemotion.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReloadPlanTest {
    @Test public void onlyScopedPackages() {
        assertEquals(ReloadPlan.SYSTEMUI, ReloadPlan.requirePackage(ReloadPlan.SYSTEMUI));
        assertEquals(ReloadPlan.SETTINGS, ReloadPlan.requirePackage(ReloadPlan.SETTINGS));
        assertThrows(IllegalArgumentException.class, () -> ReloadPlan.requirePackage("android"));
        assertThrows(IllegalArgumentException.class, () -> ReloadPlan.requirePackage("com.miui.home"));
        assertThrows(IllegalArgumentException.class, () -> ReloadPlan.requirePackage("com.android.systemui; reboot"));
    }
    @Test public void deduplicateExactPids() { assertEquals(List.of(55, 1234), ReloadPlan.pids("55 1234\n55")); }
    @Test public void noRunningProcess() { assertTrue(ReloadPlan.pids(" \n ").isEmpty()); }
    @Test public void rejectsShellSyntax() {
        for (String value : List.of("123; reboot", "$(id)", "123\nPermission denied", "-1", "0", "1", "999999999"))
            assertThrows(IllegalArgumentException.class, () -> ReloadPlan.pids(value));
    }
    @Test public void explicitTermOnly() { assertEquals("kill -TERM 123 456", ReloadPlan.killCommand(List.of(123, 456))); }
    @Test public void cannotSignalInit() { assertThrows(IllegalArgumentException.class, () -> ReloadPlan.killCommand(List.of(1))); }
    @Test public void cannotSignalAnUnboundedList() { assertThrows(IllegalArgumentException.class, () -> ReloadPlan.killCommand(List.of())); }
    @Test public void exactMainProcessIdentity() {
        assertTrue(ReloadPlan.belongsTo(ReloadPlan.SYSTEMUI, "com.android.systemui\0"));
        assertFalse(ReloadPlan.belongsTo(ReloadPlan.SYSTEMUI, "com.android.systemui:other\0"));
        assertFalse(ReloadPlan.belongsTo(ReloadPlan.SYSTEMUI, "system_server\0"));
        assertFalse(ReloadPlan.belongsTo(ReloadPlan.SETTINGS, "com.android.systemui\0"));
    }
}
