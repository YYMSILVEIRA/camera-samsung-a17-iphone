package com.ib2s.lumaa17;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Local photographic color-science-inspired pipeline.
 *
 * This is NOT Apple's ISP, a generative model, multi-frame HDR, or RAW reconstruction.
 * Uses a conservative highlight curve, adaptive shadow lift, skin-aware edge-preserving
 * luma/chroma denoising and microcontrast. Never writes into the source capture.
 */
final class IphoneLookProcessor {
    interface Progress { void onProgress(int value, String step); }
    private IphoneLookProcessor() {}

    static final int NATURAL = 0, STANDARD = 1, NIGHT = 2;

    static final class Result {
        final Uri uri;
        final String details;
        final int width, height;
        Result(Uri uri, String details, int width, int height) {
            this.uri=uri; this.details=details; this.width=width; this.height=height;
        }
    }

    static Result process(Context c, Uri original, int profile, Progress progress) throws Exception {
        if(original==null)throw new IOException("Capture uma foto antes de aplicar o estilo.");
        int preset=Math.max(NATURAL,Math.min(NIGHT,profile));
        progress.onProgress(0,"Abrindo a fotografia original");
        Bitmap source=ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(c.getContentResolver(),original),
            (decoder,info,src)->{
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                if(Math.max(w,h)>4608) {
                    float f=4608f/Math.max(w,h);
                    decoder.setTargetSize(Math.max(1,Math.round(w*f)),Math.max(1,Math.round(h*f)));
                }
            });
        Bitmap output=null;
        try {
            int width=source.getWidth(),height=source.getHeight();
            if(width<2||height<2)throw new IOException("Imagem pequena demais.");
            progress.onProgress(6,"Analisando exposição e tons");
            int[] histogram=histogram(source);
            int median=percentile(histogram,.50);
            double automaticGain=IphoneColorMath.clamp(118.0/Math.max(60,median),.91,1.13);
            int[] tone=IphoneColorMath.toneTable(preset,automaticGain);
            progress.onProgress(12,"Processando cores e ruído local");
            output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            int[] previous=new int[width],current=new int[width],next=new int[width],processed=new int[width];
            source.getPixels(current,0,width,0,0,width,1);
            System.arraycopy(current,0,previous,0,width);
            if(height>1)source.getPixels(next,0,width,0,1,width,1);
            int lumaReduction[]={12,24,40}, chromaReduction[]={40,62,79};
            for(int y=0;y<height;y++){
                for(int x=0;x<width;x++){
                    int center=current[x];
                    int r=Color.red(center),g=Color.green(center),b=Color.blue(center);
                    int luminance=IphoneColorMath.luma(r,g,b);
                    boolean skin=IphoneColorMath.skin(r,g,b);
                    int chroma=lumaReduction[preset];
                    int colorStrength=chromaReduction[preset];
                    // Smooth shadow noise more than highlights. Preserve fine facial texture.
                    double dark=.52+.48*Math.max(0,(180.0-luminance)/180.0);
                    double keepSkin=skin?.56:1.0;
                    int sumR=r*12,sumG=g*12,sumB=b*12,weight=12;
                    int left=Math.max(0,x-1),right=Math.min(width-1,x+1);
                    // Neighbour confidence protects both luminance and colored boundaries.
                    int[] neighbours={
                        previous[left],previous[x],previous[right],
                        current[left],current[right],
                        next[left],next[x],next[right]};
                    for(int pixel:neighbours){
                        int nr=Color.red(pixel),ng=Color.green(pixel),nb=Color.blue(pixel);
                        int nl=IphoneColorMath.luma(nr,ng,nb);
                        int diff=Math.abs(nl-luminance);
                        int chromaDistance=Math.abs(nr-r)+Math.abs(ng-g)+Math.abs(nb-b);
                        int confidence=diff<=8?8:diff<=16?5:diff<=28?2:0;
                        if(chromaDistance>105)confidence=0;
                        else if(chromaDistance>65)confidence=Math.min(confidence,2);
                        if(confidence==0)continue;
                        sumR+=nr*confidence;
                        sumG+=ng*confidence;
                        sumB+=nb*confidence;
                        weight+=confidence;
                    }
                    int averageR=sumR/weight,averageG=sumG/weight,averageB=sumB/weight;
                    int averageY=IphoneColorMath.luma(averageR,averageG,averageB);
                    double cRatio=(colorStrength/100.0)*dark*keepSkin;
                    double yRatio=(chroma/100.0)*dark*keepSkin;
                    // Chroma denoise is stronger than luma denoise; avoids waxy skin.
                    double rr=IphoneColorMath.mix(r,averageR,cRatio);
                    double gg=IphoneColorMath.mix(g,averageG,cRatio);
                    double bb=IphoneColorMath.mix(b,averageB,cRatio);
                    double lumaComp=IphoneColorMath.mix(luminance,averageY,yRatio)
                            -IphoneColorMath.luma((int)rr,(int)gg,(int)bb);
                    rr+=lumaComp;gg+=lumaComp;bb+=lumaComp;
                    double mapped=tone[luminance]-luminance;
                    rr+=mapped;gg+=mapped;bb+=mapped;
                    // Mild warm white balance; lighter on skin so faces remain natural.
                    double warmth=(skin?.48:1.0)*(preset==NIGHT?.9:1.0);
                    rr+=warmth*(preset==NATURAL?1.35:2.4);
                    bb-=warmth*(preset==NATURAL?1.2:2.7);
                    // Increase saturation only where already colorful; protect highlights.
                    double sat=preset==NATURAL?.025:preset==STANDARD?.042:.028;
                    if(skin)sat*=.35;
                    double neutral=(rr*77+gg*150+bb*29)/256.0;
                    rr+=(rr-neutral)*sat;gg+=(gg-neutral)*sat;bb+=(bb-neutral)*sat;
                    // Selective microcontrast. Small differences are noise, not texture.
                    double detail=luminance-averageY;
                    if(Math.abs(detail)>4&&Math.abs(detail)<45){
                        double sharp=(preset==NIGHT?.06:preset==STANDARD?.115:.075);
                        if(skin)sharp*=.55;
                        rr+=detail*sharp;gg+=detail*sharp;bb+=detail*sharp;
                    }
                    processed[x]=Color.argb(Color.alpha(center),
                            IphoneColorMath.clamp8(rr),IphoneColorMath.clamp8(gg),
                            IphoneColorMath.clamp8(bb));
                }
                output.setPixels(processed,0,width,0,y,width,1);
                int[] swap=previous;previous=current;current=next;next=swap;
                int nextY=y+2;
                if(nextY<height)source.getPixels(next,0,width,0,nextY,width,1);
                else System.arraycopy(current,0,next,0,width);
                if(y%24==0 || y==height-1)
                    progress.onProgress(12+(int)(77.0*(y+1)/height),
                            preset==NIGHT?"Reduzindo ruído de baixa luz":"Ajustando textura e cores");
            }
            progress.onProgress(92,"Gravando a cópia em alta qualidade");
            Uri saved=save(c,output,preset);
            progress.onProgress(100,"Estilo aplicado; fotografia original preservada");
            return new Result(saved,"Estilo iPhone "+profileName(preset)
                +" · ruído e cores tratados localmente · "+width+"×"+height
                +" · original preservado",width,height);
        } finally {
            if(output!=null&&!output.isRecycled())output.recycle();
            if(!source.isRecycled())source.recycle();
        }
    }

    static String profileName(int p) {
        if(p==NIGHT)return "Noturno";
        return p==NATURAL?"Natural":"Padrão";
    }

    private static int[] histogram(Bitmap image){
        int[] hist=new int[256];
        int width=image.getWidth(),height=image.getHeight();
        int step=Math.max(1,Math.max(width,height)/175);
        for(int y=step/2;y<height;y+=step)
            for(int x=step/2;x<width;x+=step){
                int color=image.getPixel(x,y);
                hist[IphoneColorMath.luma(Color.red(color),Color.green(color),Color.blue(color))]++;
            }
        return hist;
    }

    private static int percentile(int[] hist,double fraction){
        long count=0;for(int v:hist)count+=v;
        if(count==0)return 118;
        long target=(long)Math.ceil(count*fraction),total=0;
        for(int i=0;i<hist.length;i++){
            total+=hist[i];if(total>=target)return i;
        }
        return 255;
    }

    private static Uri save(Context c,Bitmap image,int profile)throws IOException{
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                "LUMA_IPHONE_"+profileName(profile).toUpperCase(Locale.US)+"_"+stamp+".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/LumaA17/EstiloIPhone");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("Não foi possível salvar a versão com estilo iPhone.");
        try{
            try(OutputStream out=c.getContentResolver().openOutputStream(uri)){
                if(out==null||!image.compress(Bitmap.CompressFormat.JPEG,98,out))
                    throw new IOException("Falha ao codificar a imagem processada.");
            }
            ContentValues ready=new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            c.getContentResolver().update(uri,ready,null,null);
            return uri;
        }catch(Exception error){
            c.getContentResolver().delete(uri,null,null);
            throw new IOException("Não foi possível salvar o estilo. O original está preservado.",error);
        }
    }
}
