package com.ib2s.lumaa17;

import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.TensorBuffer;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Real EDSR x4 neural inference from a pretrained offline TFLite model.
 * Works on tiles; never conflates interpolation with inferred photographic details.
 * Input: float32 RGB NCHW [1,3,128,128] in [0,1].
 * Output: float32 RGB NCHW [1,3,512,512] in [0,1].
 *
 * A source image may be reduced before inference to cap output RAM; the app
 * reports both original dimensions and actual output dimensions.
 */
final class NeuralUpscaler {
    interface Progress { void onProgress(int percent, String phase); }
    static final class Result {
        final Uri uri;
        final int originalWidth, originalHeight, outputWidth, outputHeight;
        final boolean gpu;
        Result(Uri uri, int ow, int oh, int w, int h, boolean gpu) {
            this.uri=uri; originalWidth=ow; originalHeight=oh;
            outputWidth=w; outputHeight=h; this.gpu=gpu;
        }
    }
    private static final String MODEL = "edsr.tflite";
    private static final int INPUT = 128, OUTPUT = 512;
    private static final int BORDER = 8, CORE = INPUT - 2 * BORDER;
    private NeuralUpscaler() {}

    static Result upscale(Context context, Uri original, boolean maximum, Progress progress) throws Exception {
        if (original == null) throw new IOException("Capture uma imagem antes de ampliar.");
        progress.onProgress(0, "Lendo dimensões da fotografia");
        final int[] originalSize = new int[2];
        // Bounded decode avoids ever allocating a full 50 MP photo just for inference.
        final int maxInput = maximum && memoryClass(context) >= 256 ? 1536 : 1024;
        Bitmap input = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.getContentResolver(), original),
                (decoder, info, source) -> {
                    int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                    originalSize[0]=w; originalSize[1]=h;
                    float scale=Math.min(1f, (float)maxInput/Math.max(w,h));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSize(Math.max(1,Math.round(w*scale)),
                            Math.max(1,Math.round(h*scale)));
                });
        Bitmap target=null;
        Uri saved=null;
        CompiledModel model=null;
        boolean gpu=false;
        try {
            int w=input.getWidth(),h=input.getHeight(), outputW=w*4,outputH=h*4;
            long pixels=(long)outputW*outputH;
            if (pixels>29_000_000L || pixels<1) throw new IOException("Imagem muito grande para super-resolução.");
            progress.onProgress(2, "Carregando o modelo EDSR offline");
            try {
                model=CompiledModel.create(context.getAssets(),MODEL,
                        new CompiledModel.Options(Accelerator.GPU),null);
                gpu=true;
            } catch(Throwable unsupported) {
                // Mali GPUs can reject a delegate; CPU remains functional.
                try {
                    model=CompiledModel.create(context.getAssets(),MODEL,
                            new CompiledModel.Options(Accelerator.CPU),null);
                } catch(Throwable cpuError) {
                    throw new IOException("Modelo de super-resolução não pôde iniciar. Confira espaço livre e compatibilidade LiteRT.",cpuError);
                }
            }
            progress.onProgress(5,"Inferência neural " + (gpu?"GPU":"CPU") + " iniciada");
            target=Bitmap.createBitmap(outputW,outputH,Bitmap.Config.ARGB_8888);
            List<TensorBuffer> ins=model.createInputBuffers(),outs=model.createOutputBuffers();
            if(ins.isEmpty()||outs.isEmpty()) throw new IOException("Tensores do modelo EDSR indisponíveis.");
            float[] inputFloats=new float[3*INPUT*INPUT];
            int[] tile=new int[INPUT*INPUT];
            int[] superPixels=new int[OUTPUT*OUTPUT];
            int[] sourceRow=new int[w*h];
            input.getPixels(sourceRow,0,w,0,0,w,h);
            int nx=(w+CORE-1)/CORE, ny=(h+CORE-1)/CORE;
            int total=nx*ny,finished=0;
            final int outPlane=OUTPUT*OUTPUT, inPlane=INPUT*INPUT;
            for(int ty=0;ty<ny;ty++){
                for(int tx=0;tx<nx;tx++){
                    int left=tx*CORE,top=ty*CORE;
                    for(int py=0;py<INPUT;py++){
                        int sourceY=Math.min(h-1,Math.max(0,top+py-BORDER));
                        for(int px=0;px<INPUT;px++){
                            int sourceX=Math.min(w-1,Math.max(0,left+px-BORDER));
                            int i=py*INPUT+px,c=sourceRow[sourceY*w+sourceX];
                            inputFloats[i]=Color.red(c)/255f;
                            inputFloats[inPlane+i]=Color.green(c)/255f;
                            inputFloats[2*inPlane+i]=Color.blue(c)/255f;
                        }
                    }
                    ins.get(0).writeFloat(inputFloats);
                    model.run(ins,outs);
                    float[] generated=outs.get(0).readFloat();
                    if(generated.length!=3*outPlane)
                        throw new IOException("Saída do modelo incompatível com EDSR x4.");
                    for(int i=0;i<outPlane;i++){
                        int red=clamp(generated[i]),green=clamp(generated[outPlane+i]),
                                blue=clamp(generated[2*outPlane+i]);
                        superPixels[i]=Color.argb(255,red,green,blue);
                    }
                    int ww=Math.min(CORE,w-left)*4,hh=Math.min(CORE,h-top)*4;
                    int[] cropped=new int[ww*hh];
                    for(int y=0;y<hh;y++)
                        System.arraycopy(superPixels,(y+BORDER*4)*OUTPUT+BORDER*4,
                                cropped,y*ww,ww);
                    target.setPixels(cropped,0,ww,left*4,top*4,ww,hh);
                    finished++;
                    int percent=5 + (int)Math.round(87.0*finished/total);
                    progress.onProgress(percent,"Super-resolução EDSR: bloco "+finished+" de "+total
                            +" ("+(gpu?"GPU":"CPU")+")");
                }
            }
            progress.onProgress(93,"Gravando a imagem na galeria");
            saved=save(context,target);
            progress.onProgress(100,"Arquivo processado e salvo");
            return new Result(saved,originalSize[0],originalSize[1],outputW,outputH,gpu);
        } finally {
            if(model!=null)model.close();
            if(target!=null&&!target.isRecycled())target.recycle();
            if(!input.isRecycled())input.recycle();
        }
    }

    private static int memoryClass(Context c){
        ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        return manager==null?192:manager.getMemoryClass();
    }
    private static int clamp(float f){
        if(!Float.isFinite(f))return 0;
        return Math.max(0,Math.min(255,Math.round(f*255f)));
    }
    private static Uri save(Context c,Bitmap output) throws IOException {
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,"LUMA_SR_EDSR_"+stamp+".jpg");
        v.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/LumaA17/SuperResolucao");
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new IOException("Falha ao criar destino da super-resolução.");
        try{
            try(OutputStream stream=c.getContentResolver().openOutputStream(uri)){
                if(stream==null||!output.compress(Bitmap.CompressFormat.JPEG,98,stream))
                    throw new IOException("Não foi possível gravar o JPEG ampliado.");
            }
            ContentValues done=new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING,0);
            c.getContentResolver().update(uri,done,null,null);
            return uri;
        }catch(Exception ex){
            c.getContentResolver().delete(uri,null,null);
            throw new IOException("Super-resolução interrompida ao salvar. Original preservado.",ex);
        }
    }
}
