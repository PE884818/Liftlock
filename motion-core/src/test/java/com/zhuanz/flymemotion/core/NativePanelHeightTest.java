package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativePanelHeightTest {
    @Test public void actualFastSpringCannotTripNativeSubpixelEnd() {
        SpringCurve c = new SpringCurve(200,.95f,20958f/841.3994f,-841.3994f);
        boolean crosses = false;
        for (int ms=0;ms<c.durationMillis;ms++) {
            float raw = 841.3994f-841.3994f*c.interpolate((float)ms/c.durationMillis);
            if(raw<1)crosses=true;
            float height=NativePanelHeight.sample(raw,2608,true,false);
            assertFalse(height>0&&height<1);
            assertTrue(height>=2);
        }
        assertTrue(crosses);
        assertEquals(0,NativePanelHeight.sample(0,2608,true,true),0);
    }
    @Test public void rawOvershootAndUnownedNativeRequestsStaySeparate() {
        assertEquals(2,NativePanelHeight.sample(-42,2608,true,false),0);
        assertEquals(.25f,NativePanelHeight.sample(.25f,2608,false,false),0);
        assertEquals(2608,NativePanelHeight.sample(2700,2608,false,false),0);
        assertEquals(0,NativePanelHeight.sample(0,2608,false,true),0);
    }
}
