package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;

public class PortraitMathTest {
    @Test public void subjectPixelsRemainExactlyIntact(){assertEquals(0xffb38662,PortraitMath.blend(0xffb38662,0xff103040,1));}
    @Test public void backgroundPixelsUseBlur(){assertEquals(0xff103040,PortraitMath.blend(0xffb38662,0xff103040,0));}
    @Test public void bilinearSamplingHonorsCornersAndCenter(){
        float[] m={0,1,1,0};assertEquals(0,PortraitMath.sample(m,2,2,0,0),0);
        assertEquals(1,PortraitMath.sample(m,2,2,1,0),0);assertEquals(.5,PortraitMath.sample(m,2,2,.5f,.5f),.0001);
        assertEquals(0,PortraitMath.sample(m,2,2,-4,-4),0);
    }
    @Test public void blurPreservesUniformColorAtBoundaries(){
        int[] p=new int[80];Arrays.fill(p,0xff3872a6);
        assertArrayEquals(p,PortraitMath.background(p,new float[80],10,8,5));
    }
    @Test public void blurDoesNotReplaceSceneWithSubjectColor(){
        int[] p=new int[25];Arrays.fill(p,0xff204080);float[] fg=new float[25];
        p[12]=0xffff0000;fg[12]=1;
        int color=PortraitMath.background(p,fg,5,5,2)[12];
        assertTrue(((color>>16)&255)<40);assertTrue((color&255)>115);
    }
}
