package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneRequestOrderTest {
    @Test public void firstRequestAndDuplicate(){var order=new SceneRequestOrder();assertTrue(order.begin(10,1));assertFalse(order.begin(10,1));assertTrue(order.current(10,1));}
    @Test public void endWhileInitializationPending(){var order=new SceneRequestOrder();order.begin(10,1);assertTrue(order.end(10,1));assertFalse(order.current(10,1));assertFalse(order.begin(10,1));}
    @Test public void endBeforeDelayedBegin(){var order=new SceneRequestOrder();order.end(10,2);assertFalse(order.begin(10,2));assertFalse(order.begin(10,1));assertTrue(order.begin(10,3));}
    @Test public void replacedSurfaceRejectsLateEnd(){var order=new SceneRequestOrder();order.begin(10,2);order.begin(10,3);assertFalse(order.end(10,2));assertTrue(order.current(10,3));assertFalse(order.current(10,2));}
    @Test public void newSessionMayRestartBinding(){var order=new SceneRequestOrder();order.begin(10,50);assertTrue(order.begin(20,1));assertFalse(order.begin(10,60));assertFalse(order.end(10,60));assertTrue(order.current(20,1));}
    @Test public void completionDoesNotReopenEndedSession(){var order=new SceneRequestOrder();order.begin(10,1);order.end(10,1);assertFalse(order.current(10,1));assertTrue(order.begin(11,1));assertFalse(order.current(10,1));}
    @Test public void invalidIdsNeverChangeOrder(){var order=new SceneRequestOrder();assertFalse(order.begin(-1,1));assertFalse(order.end(10,-1));assertTrue(order.begin(1,1));}
}
