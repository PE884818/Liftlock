package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneLeaseTest {
    @Test public void staleEndCannotCloseRetouchScene() {
        SceneLease lease = new SceneLease();
        assertTrue(lease.begin(100, 100)); assertTrue(lease.begin(110, 110));
        assertFalse(lease.end(100)); assertTrue(lease.active(120));
        assertTrue(lease.end(110)); assertFalse(lease.active(120));
    }
    @Test public void crashedClientExpiresAndLateHeartbeatCannotRevive() {
        SceneLease lease = new SceneLease(); lease.begin(100, 100);
        assertTrue(lease.renew(100, 600)); assertTrue(lease.active(2399));
        assertFalse(lease.active(2400)); assertFalse(lease.renew(100, 2400));
        lease.clear(); assertFalse(lease.begin(100, 2500)); assertTrue(lease.begin(2500, 2500));
    }
    @Test public void duplicateBeginDoesNotExtendLifetime() {
        SceneLease lease = new SceneLease(); assertFalse(lease.begin(-1, 0));
        assertTrue(lease.begin(100, 100)); assertFalse(lease.begin(100, 1800));
        assertFalse(lease.active(1900)); assertFalse(lease.end(99));
    }
}
