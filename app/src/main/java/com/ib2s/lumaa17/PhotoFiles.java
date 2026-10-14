package com.ib2s.lumaa17;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** All edits are new, atomic MediaStore entries. Originals, including their HDR metadata, stay intact. */
public final class PhotoFiles {
    private PhotoFiles() {}
    public static Bitmap decode(Context context, Uri uri, int longEdge, boolean mutable) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),uri), (decoder,info,source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(mutable);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            int w=info.getSize().getWidth(), h=info.getSize().getHeight();
            // Do not silently shrink the archival PNG/portrait. Report an unsupported source instead.
            if (longEdge == 0 && (long)w*h > 20_000_000L) throw new IllegalArgumentException("Foto grande demais para edição local. O original foi preservado.");
            if (longEdge > 0 && Math.max(w,h)>longEdge) {
                double scale=(double)longEdge/Math.max(w,h);
                decoder.setTargetSize(Math.max(1,(int)Math.round(w*scale)),Math.max(1,(int)Math.round(h*scale)));
            }
        });
    }
    public interface Writer { void write(OutputStream out) throws IOException; }
    public static Uri save(Context context,String suffix,String mime,Writer writer) throws IOException {
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,"LUMA_"+stamp+"_"+suffix+(mime.equals("image/png")?".png":".jpg"));
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/LumaA17");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Não foi possível criar a cópia. Confira o espaço livre.");
        boolean success=false;
        try {
            try(OutputStream out=context.getContentResolver().openOutputStream(uri)) {
                if(out==null) throw new IOException("Não foi possível abrir a cópia.");
                writer.write(out);
            }
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(context.getContentResolver().update(uri,ready,null,null)!=1) throw new IOException("Não foi possível concluir a cópia.");
            success=true;return uri;
        } finally { if(!success) context.getContentResolver().delete(uri,null,null); }
    }
    public static Uri png(Context context,Bitmap bitmap,String suffix) throws IOException {
        return save(context,suffix,"image/png",out -> {
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Falha ao gravar PNG.");
        });
    }
}
