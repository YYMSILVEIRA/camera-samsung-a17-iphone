package com.ib2s.lumaa17;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Offline post-capture editor. The original MediaStore Uri is never overwritten.
 * Portrait: bundled neural selfie-segmentation model, GPU delegate then CPU fallback.
 * Tone/denoise/sharpen: deterministic pixel operations (NOT neural super-resolution).
 */
final class LocalPhotoProcessor {
    private static final String TAG = "LumaLocal";
    private static final String MODEL = "selfie_segmenter.tflite";
    private LocalPhotoProcessor() {}

    static final class Result {
        final Uri uri;
        final String details;
        Result(Uri uri, String details) { this.uri = uri; this.details = details; }
    }

    static Result process(Context context, Uri original, int preset, boolean portrait,
                          int blur, boolean png) throws Exception {
        if (original == null) throw new IOException("Foto original indisponível.");
        Bitmap input = load(context, original);
        Bitmap adjusted = null;
        Bitmap finalBitmap = null;
        boolean gpu = false;
        try {
            adjusted = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(adjusted);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (preset > 0) paint.setColorFilter(autoColor(input, preset));
            canvas.drawBitmap(input, 0, 0, paint);
            if (preset >= 2) {
                // Mild neighborhood-based denoising, without introducing synthesized scene details.
                denoise(adjusted, preset == 3 ? 0.18f : 0.1f);
            }
            if (preset >= 2) sharpen(adjusted, preset == 3 ? 0.24f : 0.14f);
            if (portrait) {
                PortraitResult result = applyPortrait(context, adjusted, blur);
                finalBitmap = result.bitmap;
                gpu = result.gpu;
            } else {
                finalBitmap = adjusted;
            }
            Uri copy = save(context, finalBitmap, portrait, png);
            return new Result(copy, portrait
                    ? "Retrato local (" + (gpu ? "GPU" : "CPU") + ") · original preservado"
                    : png ? "PNG exportado · original preservado" : "Foto local salva · original preservado");
        } finally {
            if (finalBitmap != null && finalBitmap != adjusted && !finalBitmap.isRecycled()) finalBitmap.recycle();
            if (adjusted != null && !adjusted.isRecycled()) adjusted.recycle();
            if (!input.isRecycled()) input.recycle();
        }
    }

    private static Bitmap load(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int longest = Math.max(w, h);
            // Protect low-memory phones on unusually large images. A17's 4080x3060 fits.
            if (longest > 4608) {
                float factor = 4608f / longest;
                decoder.setTargetSize(Math.max(1, Math.round(w * factor)),
                        Math.max(1, Math.round(h * factor)));
            }
        });
    }

    private static ColorMatrixColorFilter autoColor(Bitmap source, int preset) {
        int w = source.getWidth(), h = source.getHeight();
        long sum = 0, count = 0;
        int step = Math.max(1, Math.max(w, h) / 145);
        for (int y = step / 2; y < h; y += step)
            for (int x = step / 2; x < w; x += step) {
                int c = source.getPixel(x, y);
                sum += (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) / 256;
                count++;
            }
        double average = count == 0 ? 120 : (double) sum / count;
        float desired = preset == 1 ? 118 : preset == 2 ? 124 : 128;
        float strength = preset == 1 ? .32f : preset == 2 ? .55f : .72f;
        float gain = (float) Math.max(.85, Math.min(1.28, 1 + (desired / Math.max(65, average) - 1) * strength));
        float saturation = preset == 1 ? 1.035f : preset == 2 ? 1.055f : 1.07f;
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(saturation);
        ColorMatrix tone = new ColorMatrix(new float[]{
                gain, 0, 0, 0, 0,
                0, gain, 0, 0, 0,
                0, 0, gain, 0, 0,
                0, 0, 0, 1, 0});
        sat.postConcat(tone);
        return new ColorMatrixColorFilter(sat);
    }

    /** Soft small-radius averaging to reduce fine noise. Two working scanlines, no extra full-res bitmap. */
    private static void denoise(Bitmap bitmap, float strength) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w < 3 || h < 3) return;
        int[] prev = new int[w], row = new int[w], next = new int[w], out = new int[w];
        bitmap.getPixels(prev, 0, w, 0, 0, w, 1);
        bitmap.getPixels(row, 0, w, 0, 1, w, 1);
        for (int y = 1; y < h - 1; y++) {
            bitmap.getPixels(next, 0, w, 0, y + 1, w, 1);
            out[0] = row[0]; out[w-1] = row[w-1];
            for (int x = 1; x < w - 1; x++) {
                int c = row[x];
                int neighbors = 4;
                int rr = (Color.red(prev[x]) + Color.red(next[x]) + Color.red(row[x-1]) + Color.red(row[x+1])) / neighbors;
                int gg = (Color.green(prev[x]) + Color.green(next[x]) + Color.green(row[x-1]) + Color.green(row[x+1])) / neighbors;
                int bb = (Color.blue(prev[x]) + Color.blue(next[x]) + Color.blue(row[x-1]) + Color.blue(row[x+1])) / neighbors;
                out[x] = Color.argb(Color.alpha(c),
                        mix(Color.red(c), rr, strength), mix(Color.green(c), gg, strength),
                        mix(Color.blue(c), bb, strength));
            }
            bitmap.setPixels(out, 0, w, 0, y, w, 1);
            int[] tmp = prev; prev = row; row = next; next = tmp;
        }
    }

    private static void sharpen(Bitmap bitmap, float amount) {
        // Conservative horizontal unsharp mask; no interpolation or fictional 2x output.
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w < 3 || h < 3) return;
        int[] row = new int[w], out = new int[w];
        for (int y = 1; y < h - 1; y++) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1);
            out[0] = row[0]; out[w-1] = row[w-1];
            for (int x=1; x<w-1; x++) {
                int center=row[x], l=row[x-1], r=row[x+1];
                out[x] = Color.argb(Color.alpha(center),
                        edge(Color.red(center), Color.red(l), Color.red(r), amount),
                        edge(Color.green(center), Color.green(l), Color.green(r), amount),
                        edge(Color.blue(center), Color.blue(l), Color.blue(r), amount));
            }
            bitmap.setPixels(out,0,w,0,y,w,1);
        }
    }

    private static int edge(int c,int l,int r,float amount) { return Math.max(0,Math.min(255, Math.round(c + amount*(2*c-l-r)))); }
    private static int mix(int a,int b,float t) { return Math.max(0,Math.min(255, Math.round(a*(1-t)+b*t))); }

    private static final class PortraitResult {
        final Bitmap bitmap;
        final boolean gpu;
        PortraitResult(Bitmap bitmap, boolean gpu) { this.bitmap=bitmap; this.gpu=gpu; }
    }

    private static PortraitResult applyPortrait(Context context, Bitmap original, int intensity) throws Exception {
        int w = original.getWidth(), h = original.getHeight();
        float inferenceScale = Math.min(1f, 960f/Math.max(w,h));
        Bitmap sample = Bitmap.createScaledBitmap(original,
                Math.max(1, Math.round(w*inferenceScale)), Math.max(1, Math.round(h*inferenceScale)), true);
        Bitmap alphaMask = null;
        boolean gpuUsed = false;
        try {
            for (int attempt=0; attempt<2 && alphaMask==null; attempt++) {
                boolean gpu = attempt == 0;
                try {
                    alphaMask = personMask(context, sample, gpu);
                    gpuUsed = gpu;
                } catch (RuntimeException | LinkageError error) {
                    if (!gpu) throw error;
                    Log.w(TAG, "GPU segmentation unavailable; trying CPU", error);
                }
            }
        } finally {
            if (sample != original) sample.recycle();
        }
        if (alphaMask == null) throw new IOException("Modelo de retrato não identificou a pessoa.");
        Bitmap blurred = null, result = null;
        try {
            float ratio = Math.min(1f, 840f/Math.max(w,h));
            blurred = Bitmap.createScaledBitmap(original,
                    Math.max(1,Math.round(w*ratio)), Math.max(1,Math.round(h*ratio)),true);
            blurSmall(blurred, intensity >= 2 ? 14 : 8);
            result = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(result);
            Rect rect = new Rect(0,0,w,h);
            Paint smooth = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(blurred,null,rect,smooth);
            int save = c.saveLayer(0,0,w,h,null);
            c.drawBitmap(original,0,0,null);
            Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            c.drawBitmap(alphaMask,null,rect,mask);
            mask.setXfermode(null);
            c.restoreToCount(save);
            return new PortraitResult(result,gpuUsed);
        } catch (Throwable ex) {
            if (result!=null) result.recycle();
            throw ex;
        } finally {
            if (blurred!=null) blurred.recycle();
            alphaMask.recycle();
        }
    }

    /** Neural person-vs-background segmentation with GPU attempt and CPU fallback. */
    private static Bitmap personMask(Context context, Bitmap image, boolean gpu) throws Exception {
        BaseOptions options = BaseOptions.builder().setModelAssetPath(MODEL)
                .setDelegate(gpu ? BaseOptions.Delegate.GPU : BaseOptions.Delegate.CPU).build();
        ImageSegmenter.ImageSegmenterOptions config = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(options).setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(false).setOutputConfidenceMasks(true).build();
        try (ImageSegmenter segmenter = ImageSegmenter.createFromOptions(context, config);
                MPImage mpImage = new BitmapImageBuilder(image).build()) {
            ImageSegmenterResult segmentation = segmenter.segment(mpImage);
            if (!segmentation.confidenceMasks().isPresent()) throw new IOException("Máscara de pessoa ausente.");
            List<MPImage> masks = segmentation.confidenceMasks().get();
            if (masks.isEmpty()) throw new IOException("Resultado de retrato sem máscaras.");
            // SelfieSegmenter category 0 = background, 1 = person.
            MPImage person = masks.get(masks.size()>1?1:0);
            int mw=person.getWidth(), mh=person.getHeight();
            FloatBuffer values = ByteBufferExtractor.extract(person)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (values.remaining()<mw*mh) throw new IOException("Máscara de IA incompleta.");
            int[] pixels = new int[mw*mh];
            for(int i=0;i<pixels.length;i++) {
                float c=Math.max(0,Math.min(1,values.get()));
                float edge=(c-.20f)/.68f;
                int a=Math.round(255*Math.max(0,Math.min(1,edge)));
                pixels[i]=Color.argb(a,255,255,255);
            }
            Bitmap result=Bitmap.createBitmap(mw,mh,Bitmap.Config.ARGB_8888);
            result.setPixels(pixels,0,mw,0,0,mw,mh);
            return result;
        }
    }

    private static void blurSmall(Bitmap bitmap, int radius) {
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] src=new int[w*h],dst=new int[src.length];
        bitmap.getPixels(src,0,w,0,0,w,h);
        // Separable box blur over a downscaled photo (memory bounded).
        for (int y=0;y<h;y++) {
            for(int x=0;x<w;x++){
                int r=0,g=0,b=0,n=0;
                for(int dx=-radius;dx<=radius;dx++){
                    int nx=Math.min(w-1,Math.max(0,x+dx));int c=src[y*w+nx];
                    r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);n++;
                }
                dst[y*w+x]=Color.rgb(r/n,g/n,b/n);
            }
        }
        int[] vertical=new int[src.length];
        for (int y=0;y<h;y++) {
            for (int x=0;x<w;x++) {
                int r=0,g=0,b=0,n=0;
                for (int dy=-radius;dy<=radius;dy++){
                    int ny=Math.min(h-1,Math.max(0,y+dy));int c=dst[ny*w+x];
                    r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);n++;
                }
                vertical[y*w+x]=Color.rgb(r/n,g/n,b/n);
            }
        }
        bitmap.setPixels(vertical,0,w,0,0,w,h);
    }

    private static Uri save(Context context, Bitmap bitmap, boolean portrait, boolean png) throws IOException {
        String ext=png?".png":".jpg";
        String mime=png?"image/png":"image/jpeg";
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,"LUMA_"+(portrait?"RETRATO_":"LOCAL_")+stamp+ext);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/LumaA17/Processadas");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Não foi possível salvar a imagem processada.");
        try {
            try(OutputStream output=context.getContentResolver().openOutputStream(uri)){
                if(output==null||!bitmap.compress(png?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,
                        png?100:98,output)) throw new IOException("Falha ao codificar imagem.");
            }
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);
            context.getContentResolver().update(uri,done,null,null);
            return uri;
        } catch(Exception ex) {
            context.getContentResolver().delete(uri,null,null);
            throw new IOException("Falha ao salvar cópia local; original preservado.",ex);
        }
    }
}
