package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DrawDeliveryTest {
    @Test public void consumingRealClientHandlerRecordsDrawWithoutAnInnerMethodHook() {
        var p = new ScenePresentation(); long epoch = p.bind(1,2,3,5);
        var delivery = new DrawDelivery(1,epoch,2,3,5,true);
        // No separate WindowState hook sets drawn=true before this handler runs.
        assertEquals(-1,p.drawSequence());
        assertTrue(delivery.consume(p,1,epoch,2,3));
        assertEquals(5,p.drawSequence()); assertFalse(p.ready());
        assertTrue(p.presented(epoch,5,true,true));
    }
    @Test public void consumedTimeoutAndNullBufferAreNotPresentation() {
        var p = new ScenePresentation(); long e=p.bind(1,2,3,5);
        assertFalse(new DrawDelivery(1,e,2,3,Integer.MAX_VALUE,true).consume(p,1,e,2,3));
        assertFalse(new DrawDelivery(1,e,2,3,5,false).consume(p,1,e,2,3));
        assertFalse(p.ready()); assertEquals(-1,p.drawSequence());
    }
    @Test public void serverInvalidationInsideNativeDrawCannotReuseItsOldEntryReceipt() {
        var p = new ScenePresentation(); long e=p.bind(1,2,3,5);
        var old=new DrawDelivery(1,e,2,3,5,true);
        p.invalidate();long current=p.bind(1,2,4,6);
        assertFalse(old.consume(p,1,current,2,4));
        assertFalse(p.presented(e,5,true,true));
    }
    @Test public void clientSequenceMustMeetTheCurrentRequest() {
        var p=new ScenePresentation();long e=p.bind(1,2,3,8);
        assertFalse(new DrawDelivery(1,e,2,3,7,true).consume(p,1,e,2,3));
        assertTrue(new DrawDelivery(1,e,2,3,8,true).consume(p,1,e,2,3));
    }
}
