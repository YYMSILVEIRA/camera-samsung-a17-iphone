package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import org.junit.Test;

public class PhotoResolutionPolicyTest {
    @Test public void a17MainDoesNotFallBackToOnePointSixMegapixels(){
        assertArrayEquals(new int[]{4080,3060},PhotoResolutionPolicy.balanced(
                new int[][]{{2336,1080},{1920,1080},{1440,1440},{1440,1080},{640,480}},
                new int[][]{{4080,3060},{4080,2296},{4080,1884},{3056,3056}}));
    }
    @Test public void a17FrontUsesThirteenMegapixelPath(){
        assertArrayEquals(new int[]{4128,3096},PhotoResolutionPolicy.balanced(
                new int[][]{{1440,1080}},new int[][]{{4128,3096},{4128,2322},{3088,3088}}));
    }
    @Test public void alternateCameraUsesItsRealFiveMegapixels(){
        assertArrayEquals(new int[]{2576,1932},PhotoResolutionPolicy.balanced(
                new int[][]{{1440,1080}},new int[][]{{2576,1932},{2560,1440}}));
    }
    @Test public void worksWithoutHighResolutionList(){
        assertArrayEquals(new int[]{3408,2556},PhotoResolutionPolicy.balanced(
                new int[][]{{3712,2556},{3408,2556},{1440,1080}},null));
    }
}
