package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BufferDrawRequestTest {
    @Test public void coalescedNativeResizeCannotClearOutstandingBufferRequest() {
        int sync=20, buffer=21;
        // Target reportResized consumes mBufferSeqId when emitting syncWithBuffers=true.
        buffer=BufferDrawRequest.preserve(buffer,sync);
        assertTrue(buffer>sync);sync=buffer;
        // Without preservation the following native resize would emit false and seq22.
        assertFalse(buffer>sync);
        buffer=BufferDrawRequest.preserve(buffer,sync);
        assertEquals(22,buffer);assertTrue(buffer>sync);
        var proof=new ScenePresentation();long e=proof.bind(1,2,3,21);
        assertFalse(proof.drawn(e,22,false));assertFalse(proof.ready());
        assertTrue(proof.drawn(e,22,true));assertTrue(proof.presented(e,22,true,true));
    }
    @Test public void pendingOnlySelectedAuthenticatedCoveredWindow() {
        assertTrue(BufferDrawRequest.pending(true,true,true,true,false,-1));
        assertFalse(BufferDrawRequest.pending(false,true,true,true,false,-1));
        assertFalse(BufferDrawRequest.pending(true,false,true,true,false,-1));
        assertFalse(BufferDrawRequest.pending(true,true,false,true,false,-1));
        assertFalse(BufferDrawRequest.pending(true,true,true,false,false,-1));
        assertFalse(BufferDrawRequest.pending(true,true,true,true,true,-1));
        assertFalse(BufferDrawRequest.pending(true,true,true,true,false,22));
    }
    @Test public void preservationNeverRewindsNativeSequence() {
        assertEquals(25,BufferDrawRequest.preserve(25,21));
        assertEquals(22,BufferDrawRequest.preserve(21,21));
    }
    @Test public void emptyDrawThenRetryRejectsOldBindingFence() {
        var p=new ScenePresentation();long old=p.bind(1,2,3,21);
        assertFalse(p.drawn(old,22,false));
        long retry=p.bind(1,2,3,23);
        assertFalse(p.presented(old,22,true,true));
        assertFalse(p.drawn(retry,22,true));
        assertTrue(p.drawn(retry,23,true));assertTrue(p.presented(retry,23,true,true));
    }
    @Test(expected=IllegalStateException.class) public void sentinelCannotBecomeDrawRequest() {
        BufferDrawRequest.preserve(0,Integer.MAX_VALUE);
    }
}
