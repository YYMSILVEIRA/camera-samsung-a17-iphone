package com.ib2s.lumaa17;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import java.nio.ByteBuffer;

/** Bundled person segmentation, on-device. The full-size subject is never downsampled. */
public final class PortraitProcessor {
    private PortraitProcessor() {}
    public static Uri process(Context context,Uri original,int strength) throws Exception {
        Bitmap full=null, analysis=null, background=null, strip=null;
        Segmenter segmenter=Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build());
        try {
            // ImageDecoder applies EXIF rotation and mirroring before segmentation/compositing.
            analysis=PhotoFiles.decode(context,original,768,false);
            SegmentationMask result=Tasks.await(segmenter.process(InputImage.fromBitmap(analysis,0)));
            int mw=result.getWidth(),mh=result.getHeight();
            float[] mask=new float[mw*mh];ByteBuffer buffer=result.getBuffer();buffer.rewind();
            double total=0;for(int i=0;i<mask.length;i++){mask[i]=buffer.getFloat();total+=mask[i];}
            if(total/mask.length<.008) throw new IllegalStateException("Não encontrei uma pessoa. A foto original está salva; tente um enquadramento mais próximo.");
            int bw=analysis.getWidth(),bh=analysis.getHeight();
            int[] pixels=new int[bw*bh];analysis.getPixels(pixels,0,bw,0,0,bw,bh);
            float[] fg=new float[pixels.length];
            for(int y=0;y<bh;y++)for(int x=0;x<bw;x++)fg[y*bw+x]=PortraitMath.sample(mask,mw,mh,(x+.5f)*mw/bw-.5f,(y+.5f)*mh/bh-.5f);
            int radius=3+Math.max(1,Math.min(10,strength))*2;
            background=Bitmap.createBitmap(PortraitMath.background(pixels,fg,bw,bh,radius),bw,bh,Bitmap.Config.ARGB_8888);
            analysis.recycle();analysis=null;
            full=PhotoFiles.decode(context,original,0,true);
            int w=full.getWidth(),h=full.getHeight(),rows=32;
            strip=Bitmap.createBitmap(w,rows,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(strip);Paint filter=new Paint(Paint.FILTER_BITMAP_FLAG);
            int[] source=new int[w*rows],blurred=new int[w*rows];
            for(int y=0;y<h;y+=rows){
                int count=Math.min(rows,h-y);
                canvas.drawBitmap(background,null,new Rect(0,-y,w,h-y),filter);
                strip.getPixels(blurred,0,w,0,0,w,count);full.getPixels(source,0,w,0,y,w,count);
                for(int row=0;row<count;row++)for(int x=0;x<w;x++){
                    float a=PortraitMath.sample(mask,mw,mh,(x+.5f)*mw/w-.5f,(y+row+.5f)*mh/h-.5f);
                    int i=row*w+x;source[i]=PortraitMath.blend(source[i],blurred[i],a);
                }
                full.setPixels(source,0,w,0,y,w,count);
            }
            return PhotoFiles.png(context,full,"RETRATO");
        } finally {
            segmenter.close();
            if(full!=null)full.recycle();if(analysis!=null)analysis.recycle();
            if(background!=null)background.recycle();if(strip!=null)strip.recycle();
        }
    }
}
