package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real file smoke test. An emulator cannot validate the A17's optical quality. */
@RunWith(AndroidJUnit4.class)
public class CameraSmokeTest {
    @Test public void savesPhotosAndPlayableVideo() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant " + context.getPackageName() + " " + Manifest.permission.CAMERA);
        device.executeShellCommand("pm grant " + context.getPackageName() + " " + Manifest.permission.RECORD_AUDIO);
        context.startActivity(new Intent(context, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitReady(device);
        capturePhoto(device, context);
        assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "camera-fullscreen.png")));
        device.findObject(By.desc("Abrir a última foto ou vídeo")).click();
        assertTrue(device.wait(Until.hasObject(By.text("PNG")),15000));
        device.findObject(By.text("PNG")).click();
        UiObject2 confirm = device.wait(Until.findObject(By.res("android:id/button1")),15000);
        assertNotNull(confirm); confirm.click();
        assertTrue(device.wait(Until.hasObject(By.text("PNG SDR salvo · mesma resolução do original")),45000));
        assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "photo-review.png")));
        device.findObject(By.text("Voltar à câmera")).click();
        assertTrue(device.wait(Until.hasObject(By.desc("Alternar câmera frontal e traseira")),15000));
        device.findObject(By.desc("Alternar câmera frontal e traseira")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Pronto · Frontal")), 45000));
        capturePhoto(device, context);
        device.findObject(By.desc("Modo VÍDEO")).click();
        waitReady(device);
        device.findObject(By.desc("Iniciar gravação de vídeo")).click();
        assertTrue(device.wait(Until.hasObject(By.desc("Parar gravação e salvar vídeo")), 30000));
        SystemClock.sleep(3000);
        device.findObject(By.desc("Pausar ou retomar vídeo")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Retomar")), 15000));
        device.findObject(By.desc("Pausar ou retomar vídeo")).click();
        SystemClock.sleep(1500);
        device.findObject(By.desc("Parar gravação e salvar vídeo")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Vídeo salvo na galeria")), 45000));
        Uri video = latest(context, true);
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(context, video);
            assertTrue(Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 1000);
            assertNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        }
        device.pressHome();
        context.startActivity(new Intent(context, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(device.wait(Until.hasObject(By.desc("Iniciar gravação de vídeo")), 30000));
        device.pressBack();
        assertTrue(device.wait(Until.gone(By.desc("Iniciar gravação de vídeo")),15000));
    }
    private void waitReady(UiDevice device) {
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Pronto ·")),45000));
    }
    private void capturePhoto(UiDevice device,Context context) throws Exception {
        UiObject2 shutter = device.wait(Until.findObject(By.desc("Tirar foto")),30000);
        assertNotNull(shutter); shutter.click();
        assertTrue(device.wait(Until.hasObject(By.text("Foto salva na galeria")),45000));
        try (var input=context.getContentResolver().openInputStream(latest(context,false))) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds=true;
            BitmapFactory.decodeStream(input,null,options);
            assertTrue(options.outWidth > 100 && options.outHeight > 100);
        }
    }
    private Uri latest(Context context,boolean video) {
        Uri collection=video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{"DCIM/LumaA17/"},MediaStore.MediaColumns._ID+" DESC")) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            return android.content.ContentUris.withAppendedId(collection,cursor.getLong(0));
        }
    }
}
