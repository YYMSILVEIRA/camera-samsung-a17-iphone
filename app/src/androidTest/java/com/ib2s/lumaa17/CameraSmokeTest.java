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
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

/** Real file smoke test. An emulator cannot validate the A17's optical quality. */
@RunWith(AndroidJUnit4.class)
public class CameraSmokeTest {
    @Rule public final TestWatcher failureEvidence = new TestWatcher() {
        @Override protected void failed(Throwable failure, Description description) {
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"failure.png"));
                device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null),"failure.xml"));
                preserveEvidence(context,device);
            } catch (Exception ignored) { }
        }
    };
    @Test public void savesPhotosAndPlayableVideo() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant " + context.getPackageName() + " " + Manifest.permission.CAMERA);
        device.executeShellCommand("pm grant " + context.getPackageName() + " " + Manifest.permission.RECORD_AUDIO);
        context.startActivity(new Intent(context, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitReady(device);
        capturePhoto(device, context);
        Uri original = latest(context,false);
        assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "camera-fullscreen.png")));
        device.findObject(By.desc("Abrir a última foto ou vídeo")).click();
        assertTrue(device.wait(Until.hasObject(By.text("PNG")),15000));
        device.findObject(By.text("PNG")).click();
        UiObject2 confirm = device.wait(Until.findObject(By.res("android:id/button1")),15000);
        assertNotNull(confirm); confirm.click();
        Uri png = awaitPng(context,original);
        assertEquals("image/png",context.getContentResolver().getType(png));
        assertTrue(device.wait(Until.hasObject(By.text("Cópia").enabled(true)),15000));
        device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null),"photo-review.xml"));
        assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "photo-review.png")));
        preserveEvidence(context,device);
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
        // Leave the camera stopped before the remaining image-processing tests.
        device.pressHome();
    }
    private static void preserveEvidence(Context context,UiDevice device) throws Exception {
        // AGP uninstalls the target after testing, deleting its external-files directory.
        // Copy these synthetic emulator screenshots before teardown.
        device.executeShellCommand("mkdir -p /sdcard/Download/luma-ui");
        device.executeShellCommand("cp -R "+context.getExternalFilesDir(null).getAbsolutePath()+"/. /sdcard/Download/luma-ui/");
    }
    private void waitReady(UiDevice device) {
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Pronto ·")),45000));
    }
    private Uri awaitPng(Context context,Uri original) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<deadline){
            Uri candidate=latest(context,false);
            if(!candidate.equals(original)&&"image/png".equals(context.getContentResolver().getType(candidate))){
                try(var input=context.getContentResolver().openInputStream(candidate)){
                    BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;
                    BitmapFactory.decodeStream(input,null,info);
                    if(info.outWidth>100&&info.outHeight>100)return candidate;
                }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("A revisão não publicou um PNG válido na galeria");
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
