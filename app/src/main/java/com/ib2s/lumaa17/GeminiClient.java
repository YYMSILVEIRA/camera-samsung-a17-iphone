package com.ib2s.lumaa17;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** One explicit still-image request, never a live camera stream. No automatic paid retries. */
public final class GeminiClient {
    private GeminiClient(){}
    public static Uri enhance(Context context,Uri original) throws Exception {
        GeminiKeyStore keys=new GeminiKeyStore(context);
        String apiKey=keys.read();GeminiKeyStore.validate(apiKey);
        String image;
        Bitmap bitmap=PhotoFiles.decode(context,original,4096,false);
        String aspect=bitmap.getHeight()>=bitmap.getWidth()?"3:4":"4:3";
        try(ByteArrayOutputStream encoded=new ByteArrayOutputStream()){
            // Decode/re-encode only the upload: strips EXIF/location. The on-device original is untouched.
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,100,encoded))throw new IOException("Falha ao preparar a imagem.");
            if(encoded.size()>15_000_000)throw new IOException("Foto grande demais para envio. O original está salvo.");
            image=Base64.encodeToString(encoded.toByteArray(),Base64.NO_WRAP);
        }finally{bitmap.recycle();}
        JSONObject request=buildRequest(keys.model(),keys.size(),aspect,image);
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
        try{
            connection.setConnectTimeout(20_000);connection.setReadTimeout(120_000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("x-goog-api-key",apiKey);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
            try(var out=connection.getOutputStream()){out.write(body);}
            int status=connection.getResponseCode();
            if(status<200||status>=300)throw new IOException(statusMessage(status));
            byte[] response;
            try(InputStream in=connection.getInputStream()){response=readLimited(in,40_000_000);}
            Image result=parseImage(new JSONObject(new String(response,StandardCharsets.UTF_8)));
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(result.bytes,0,result.bytes.length,bounds);
            if(bounds.outWidth<64||bounds.outHeight<64||(long)bounds.outWidth*bounds.outHeight>20_000_000L
                    || !result.mime.equals(bounds.outMimeType))throw new IOException("O Gemini retornou uma imagem inválida. O original está salvo.");
            return PhotoFiles.save(context,"GEMINI",result.mime,out->out.write(result.bytes));
        }finally{connection.disconnect();}
    }
    static JSONObject buildRequest(String model,String size,String aspect,String base64) throws Exception {
        return new JSONObject().put("model",model).put("store",false)
                .put("input",new JSONArray()
                    .put(new JSONObject().put("type","text").put("text","Improve this photograph conservatively: recover natural shadow visibility, reduce noise and retain realistic texture, skin and highlights. Preserve identity, expression, geometry, framing, text and all scene objects. Do not add, remove or beautify features; do not invent detail. Return the edited photograph."))
                    .put(new JSONObject().put("type","image").put("mime_type","image/jpeg").put("data",base64)))
                .put("response_format",new JSONObject().put("type","image").put("mime_type","image/png").put("aspect_ratio",aspect).put("image_size",size));
    }
    static final class Image {
        final byte[] bytes;final String mime;
        Image(byte[] bytes,String mime){this.bytes=bytes;this.mime=mime;}
    }
    static Image parseImage(JSONObject response) throws Exception {
        JSONArray steps=response.optJSONArray("steps");
        if(steps!=null)for(int i=steps.length()-1;i>=0;i--){
            JSONObject step=steps.optJSONObject(i);if(step==null||!"model_output".equals(step.optString("type")))continue;
            JSONArray content=step.optJSONArray("content");if(content==null)continue;
            for(int j=content.length()-1;j>=0;j--){
                JSONObject part=content.optJSONObject(j);if(part==null||!"image".equals(part.optString("type")))continue;
                String mime=part.optString("mime_type"),data=part.optString("data");
                if((mime.equals("image/png")||mime.equals("image/jpeg"))&&!data.isEmpty()&&data.length()<36_000_000)
                    return new Image(Base64.decode(data,Base64.DEFAULT),mime);
            }
        }
        throw new IOException("O Gemini não retornou uma imagem. Verifique se o modelo aceita edição de fotos e tente outra imagem.");
    }
    static byte[] readLimited(InputStream input,int maximum) throws IOException {
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];int count;
            while((count=input.read(block))!=-1){if(out.size()+count>maximum)throw new IOException("Resposta grande demais. O original está salvo.");out.write(block,0,count);}
            return out.toByteArray();
        }
    }
    static String statusMessage(int code){
        if(code==401||code==403)return "Chave recusada ou projeto sem acesso. Confira a chave e a ativação da API nos ajustes.";
        if(code==429)return "Cota ou limite de uso do Gemini atingido. Confira sua conta antes de tentar novamente.";
        if(code==400||code==404)return "Modelo ou configuração indisponível. Confira o modelo de imagem e a cobrança no AI Studio.";
        return "O Gemini não concluiu a solicitação (HTTP "+code+"). A foto original está salva.";
    }
}
