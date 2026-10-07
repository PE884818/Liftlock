package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MirrorBindingTest {
    @Test public void sourceNeedsCommittedAuthenticatedDrawnMirror() {
        var binding = new MirrorBinding(); binding.begin(10, 2, 80);
        assertFalse(binding.canDetach(true, true));
        assertTrue(binding.acknowledge(10, 2, 80));
        assertFalse(binding.canDetach(false, true)); assertFalse(binding.canDetach(true, false));
        assertTrue(binding.canDetach(true, true)); assertFalse(binding.ready());
        binding.detached(); assertTrue(binding.ready());
    }
    @Test public void delayedReceiptCannotDetachReplacementSource() {
        var binding = new MirrorBinding(); binding.begin(10, 2, 80); binding.begin(10, 3, 81);
        assertFalse(binding.acknowledge(10, 2, 80)); assertFalse(binding.canDetach(true, true));
        assertFalse(binding.acknowledge(11, 3, 81)); assertFalse(binding.acknowledge(10, 3, 80));
        assertTrue(binding.acknowledge(10, 3, 81));
        binding.clear(); assertFalse(binding.acknowledge(10, 3, 81)); assertFalse(binding.ready());
    }
    @Test public void blurSheetHasNoContentOvershoot() {
        var frame = MotionFrame.sample(2075, 100, 2000, 2400, true, 1);
        assertEquals(75, frame.contentY(), .001); assertEquals(0, frame.blurY(), .001);
        frame = MotionFrame.sample(1000, -100, 2000, 2400, true, 1);
        assertEquals(-1200, frame.blurY(), .001); assertEquals(1200, frame.wallpaperHeight(), .001);
        assertEquals(-1200, frame.withWallpaperProgress(1).blurY(), .001);
        assertEquals(0, MotionFrame.sample(1000, 0, 2000, false, 1).blurY(), .001);
    }
}
