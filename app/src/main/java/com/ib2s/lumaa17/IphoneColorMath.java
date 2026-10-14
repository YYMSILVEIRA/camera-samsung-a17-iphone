package com.ib2s.lumaa17;

/** Pure, deterministic components for photo tone curves and pixel safety. */
final class IphoneColorMath {
    private IphoneColorMath(){}
    static int luma(int r,int g,int b){return (77*r+150*g+29*b)>>8;}
    static boolean skin(int r,int g,int b){
        return r>g && g>b && r-g>6 && r-b>15 && r>78 && g>40
                && (r-b)<155 && (r-g)<100;
    }
    static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
    static int clamp8(double v){return (int)Math.max(0,Math.min(255,Math.round(v)));}
    static double mix(double a,double b,double part){return a*(1-part)+b*part;}
    static int[] toneTable(int profile,double gain){
        int preset=Math.max(0,Math.min(2,profile));
        double[] shadows={8,16,29},highlights={5,10,14};
        int[] values=new int[256];
        for(int v=0;v<256;v++){
            double base=v*clamp(gain,.91,1.13);
            double normalized=clamp(base/255.0,0,1);
            double lifted=shadows[preset]*Math.pow(1-normalized,2)
                    *(base/(base+20.0)); // true blacks remain black
            double rolled=highlights[preset]*Math.pow(normalized,5);
            values[v]=clamp8(base+lifted-rolled);
        }
        // Tone map must not reverse local luminance ordering.
        for(int i=1;i<256;i++)if(values[i]<values[i-1])values[i]=values[i-1];
        return values;
    }
}
