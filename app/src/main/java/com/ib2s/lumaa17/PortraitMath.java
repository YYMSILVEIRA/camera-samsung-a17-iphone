package com.ib2s.lumaa17;

/** CPU background blur and feathering, independent of Android for numerical tests. */
public final class PortraitMath {
    private PortraitMath() {}
    public static float sample(float[] mask, int w, int h, float x, float y) {
        x = Math.max(0, Math.min(w - 1, x)); y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int)x, y0 = (int)y, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float fx = x - x0, fy = y - y0;
        return (mask[y0*w+x0]*(1-fx) + mask[y0*w+x1]*fx)*(1-fy)
                + (mask[y1*w+x0]*(1-fx) + mask[y1*w+x1]*fx)*fy;
    }
    public static float feather(float probability) {
        float x = Math.max(0, Math.min(1, (probability - .15f) / .7f));
        return x*x*(3-2*x);
    }
    public static int blend(int original, int background, float foreground) {
        float a = feather(foreground);
        int r = Math.round(((original >> 16)&255)*a + ((background >> 16)&255)*(1-a));
        int g = Math.round(((original >> 8)&255)*a + ((background >> 8)&255)*(1-a));
        int b = Math.round((original&255)*a + (background&255)*(1-a));
        return 0xff000000 | (r<<16) | (g<<8) | b;
    }
    /** Person pixels contribute less to the blurred background, reducing colored halos. */
    public static int[] background(int[] pixels, float[] foreground, int width, int height, int radius) {
        float[] weighted = new float[pixels.length * 4], tmp = new float[weighted.length];
        for (int i=0; i<pixels.length; i++) {
            float weight = Math.max(.005f, 1-feather(foreground[i]));
            weighted[i*4] = ((pixels[i]>>16)&255)*weight;
            weighted[i*4+1] = ((pixels[i]>>8)&255)*weight;
            weighted[i*4+2] = (pixels[i]&255)*weight; weighted[i*4+3] = weight;
        }
        for (int pass=0; pass<3; pass++) {
            box(weighted, tmp, width, height, radius, true);
            box(tmp, weighted, width, height, radius, false);
        }
        int[] result = new int[pixels.length];
        for (int i=0; i<result.length; i++) {
            float weight = Math.max(.0001f, weighted[i*4+3]);
            int r=channel(weighted[i*4]/weight), g=channel(weighted[i*4+1]/weight), b=channel(weighted[i*4+2]/weight);
            result[i] = 0xff000000 | (r<<16) | (g<<8) | b;
        }
        return result;
    }
    private static int channel(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
    private static void box(float[] input, float[] output, int w, int h, int radius, boolean horizontal) {
        int lines=horizontal?h:w, length=horizontal?w:h, window=2*radius+1;
        for (int line=0; line<lines; line++) for (int c=0; c<4; c++) {
            double sum=0;
            for (int n=-radius;n<=radius;n++) sum += input[index(line, Math.max(0,Math.min(length-1,n)),w,horizontal)+c];
            for (int pos=0;pos<length;pos++) {
                output[index(line,pos,w,horizontal)+c]=(float)(sum/window);
                sum -= input[index(line,Math.max(0,pos-radius),w,horizontal)+c];
                sum += input[index(line,Math.min(length-1,pos+radius+1),w,horizontal)+c];
            }
        }
    }
    private static int index(int line,int pos,int width,boolean horizontal) { return (horizontal?line*width+pos:pos*width+line)*4; }
}
