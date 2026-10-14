package com.ib2s.lumaa17;

import org.junit.Test;
import static org.junit.Assert.*;

public class IphoneColorMathTest {
    @Test public void toneCurveDoesNotReverseLuminance() {
        for(int profile=0;profile<=2;profile++){
            for(double gain:new double[]{.91,1,1.13}){
                int[] curve=IphoneColorMath.toneTable(profile,gain);
                assertEquals(256,curve.length);
                assertEquals(0,curve[0]);
                for(int i=1;i<curve.length;i++){
                    assertTrue("tone order at "+i,curve[i]>=curve[i-1]);
                    assertTrue(curve[i]>=0&&curve[i]<=255);
                }
            }
        }
    }
    @Test public void strongerNightProfileLiftsShadowMore() {
        int[] natural=IphoneColorMath.toneTable(IphoneLookProcessor.NATURAL,1);
        int[] night=IphoneColorMath.toneTable(IphoneLookProcessor.NIGHT,1);
        assertTrue(night[45]>natural[45]);
        assertTrue(night[215]<=natural[215]);
    }
    @Test public void naturalSkinHeuristicPrefersWarmMidtones() {
        assertTrue(IphoneColorMath.skin(188,139,106));
        assertFalse(IphoneColorMath.skin(20,25,31));
        assertFalse(IphoneColorMath.skin(105,145,206));
    }
    @Test public void clippingPreservesBounds() {
        assertEquals(0,IphoneColorMath.clamp8(-200));
        assertEquals(255,IphoneColorMath.clamp8(500));
        assertEquals(127,IphoneColorMath.clamp8(127));
    }
}
