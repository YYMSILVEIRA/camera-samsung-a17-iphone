package com.ib2s.lumaa17;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PhotoProcessingTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void pngRoundTripPreservesEveryPixelAndResolution()throws Exception{
        Bitmap source=Bitmap.createBitmap(4080,3060,Bitmap.Config.ARGB_8888);source.eraseColor(0xff396d9c);
        source.setPixel(123,456,Color.RED);source.setPixel(4079,3059,Color.GREEN);
        Uri saved=null;
        try{
            saved=PhotoFiles.png(context(),source,"TEST");Bitmap decoded=PhotoFiles.decode(context(),saved,0,false);
            try{assertEquals(4080,decoded.getWidth());assertEquals(3060,decoded.getHeight());assertTrue(source.sameAs(decoded));}
            finally{decoded.recycle();}
        }finally{source.recycle();if(saved!=null)context().getContentResolver().delete(saved,null,null);}
    }
    @Test public void bundledPortraitModelLoadsOfflineAndRejectsAnEmptyScene()throws Exception{
        Bitmap blank=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);blank.eraseColor(Color.GRAY);
        Uri original=PhotoFiles.png(context(),blank,"TEST_EMPTY");blank.recycle();Uri edited=null;
        try{
            try{edited=PortraitProcessor.process(context(),original,5);fail("Empty scene should not create a fake portrait");}
            catch(IllegalStateException e){assertTrue(e.getMessage().contains("pessoa"));}
            try(var input=context().getContentResolver().openInputStream(original)){assertNotNull(input);assertTrue(input.read()!=-1);}
        }finally{context().getContentResolver().delete(original,null,null);if(edited!=null)context().getContentResolver().delete(edited,null,null);}
    }
    @Test public void personalKeyIsEncryptedAndCanBeReplaced()throws Exception{
        GeminiKeyStore vault=new GeminiKeyStore(context());
        String one="AIzaSy_TEST_ONLY_000000000000000000000000",two="AIzaSy_TEST_ONLY_111111111111111111111111";
        try{
            vault.save(one);assertEquals(one,vault.read());
            assertFalse(context().getSharedPreferences("luma_gemini",Context.MODE_PRIVATE).getAll().toString().contains(one));
            vault.save(two);assertEquals(two,vault.read());vault.clear();assertFalse(vault.configured());
            try{GeminiKeyStore.validate("gen-lang-client-0000000000");fail("Project IDs cannot authenticate");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("projeto"));}
        }finally{vault.clear();}
    }
    @Test public void geminiProtocolIsStatelessAndAcceptsOnlyModelImageOutput()throws Exception{
        JSONObject request=GeminiClient.buildRequest("test-model","4K","3:4","sample");
        assertFalse(request.getBoolean("store"));assertEquals("4K",request.getJSONObject("response_format").getString("image_size"));
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);byte[] png;
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();}finally{bitmap.recycle();}
        JSONObject part=new JSONObject().put("type","image").put("mime_type","image/png").put("data",Base64.encodeToString(png,Base64.NO_WRAP));
        JSONObject response=new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("type","model_output").put("content",new JSONArray().put(part))));
        assertArrayEquals(png,GeminiClient.parseImage(response).bytes);
        response.getJSONArray("steps").getJSONObject(0).put("type","user_input");
        try{GeminiClient.parseImage(response);fail("Never treat echoed input as edited output");}catch(java.io.IOException expected){}
        try{GeminiClient.readLimited(new ByteArrayInputStream(new byte[12]),10);fail("Enforce response size bound");}catch(java.io.IOException expected){}
    }
}
