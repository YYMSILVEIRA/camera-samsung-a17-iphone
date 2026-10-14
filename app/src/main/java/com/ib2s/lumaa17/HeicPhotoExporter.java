package com.ib2s.lumaa17;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.heifwriter.HeifWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Optional on-device HEIC export. This encodes already captured JPEG pixels
 * using the device HEIF/HEVC codec; it does NOT recover original RAW data.
 */
final class HeicPhotoExporter {
    private HeicPhotoExporter() {}

    static Uri export(Context context, Uri jpeg) throws Exception {
        if (jpeg == null) throw new IOException("Capture uma foto antes de exportar HEIC.");
        Bitmap original = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.getContentResolver(), jpeg),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    if (Math.max(w,h) > 4608) {
                        float f=4608f/Math.max(w,h);
                        decoder.setTargetSize(Math.max(1, Math.round(w*f)),Math.max(1,Math.round(h*f)));
                    }
                });
        Uri result = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "LUMA_HEIC_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                            .format(new Date()) + ".heic");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/heic");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/LumaA17/Processadas");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            result = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if (result == null) throw new IOException("Não foi possível preparar o arquivo HEIC.");
            try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(result,"w")) {
                if (fd == null) throw new IOException("Não foi possível abrir o arquivo HEIC.");
                try (HeifWriter writer = new HeifWriter.Builder(
                        fd.getFileDescriptor(), original.getWidth(), original.getHeight(),
                        HeifWriter.INPUT_MODE_BITMAP).setQuality(95).setMaxImages(1).build()) {
                    writer.start();
                    writer.addBitmap(original);
                    writer.stop(30_000_000L);
                }
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(result,publish,null,null);
            return result;
        } catch (Exception ex) {
            if (result != null) context.getContentResolver().delete(result,null,null);
            throw new IOException("Não foi possível codificar HEIC no dispositivo. "
                    + "O codificador HEIF pode estar indisponível. JPEG original preservado.",ex);
        } finally {
            original.recycle();
        }
    }
}
