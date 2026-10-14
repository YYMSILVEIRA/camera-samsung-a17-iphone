package com.ib2s.lumaa17;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Optional user-triggered cloud image generation. Not a sensor enhancement pipeline.
 * Call on a background thread, never on the camera or UI thread.
 */
final class GeminiPhotoEnhancer {
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-image:generateContent";
    private static final int INPUT_LIMIT = 10 * 1024 * 1024;
    private static final int RESPONSE_LIMIT = 36 * 1024 * 1024;
    private GeminiPhotoEnhancer() {}

    static Uri improve(Context context, Uri original, String key, int style) throws Exception {
        if (key == null || key.trim().isEmpty())
            throw new IllegalArgumentException("Configure uma chave Gemini válida nos Ajustes.");
        byte[] photo = input(context, original);
        String prompt = "Edit this real photograph conservatively. Keep the same people, faces, objects, "
                + "text, scene, crop and framing. No retouching of facial identity, no invented details, "
                + "no removed objects. Correct white balance, exposure, dynamic range and realistic noise "
                + "without changing the content. Preserve a natural photographic appearance.";
        if (style == 1) prompt += " Apply gentle detail sharpening and avoid halos.";
        if (style == 2) prompt += " Prioritize natural shadow detail and controlled highlights in low light.";
        JSONObject request = new JSONObject()
            .put("contents", new JSONArray().put(new JSONObject()
                .put("parts", new JSONArray()
                    .put(new JSONObject().put("text", prompt))
                    .put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(photo, Base64.NO_WRAP)))))))
            .put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("TEXT").put("IMAGE")));
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(90000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("x-goog-api-key", key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                if (code == 401 || code == 403) throw new IOException("Chave Gemini recusada (HTTP " + code + "). Confira se é uma API key do Google AI Studio.");
                if (code == 429) throw new IOException("Limite de uso do Gemini atingido (HTTP 429).");
                throw new IOException("O serviço Gemini recusou a imagem (HTTP " + code + ").");
            }
            byte[] response;
            try (InputStream in = connection.getInputStream()) { response = readLimited(in, RESPONSE_LIMIT); }
            JSONObject body = new JSONObject(new String(response, StandardCharsets.UTF_8));
            JSONArray candidates = body.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0)
                throw new IOException("O Gemini não retornou uma imagem.");
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) continue;
                JSONObject content = candidate.optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null || part.optBoolean("thought", false)) continue;
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = part.optJSONObject("inline_data");
                    if (inline == null) continue;
                    String mime = inline.optString("mimeType", inline.optString("mime_type", ""));
                    if (!mime.equals("image/png") && !mime.equals("image/jpeg") && !mime.equals("image/webp"))
                        continue;
                    byte[] generated = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                    if (generated.length < 128 || generated.length > RESPONSE_LIMIT)
                        throw new IOException("O Gemini retornou uma imagem inválida.");
                    return saveCopy(context, generated, mime);
                }
            }
            throw new IOException("O Gemini respondeu, mas não gerou uma imagem editada.");
        } finally {
            connection.disconnect();
        }
    }

    /** Use the native JPEG when manageable; resize only the uploaded COPY when too big. */
    private static byte[] input(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Foto original indisponível.");
            try { return readLimited(in, INPUT_LIMIT); }
            catch (IOException tooBig) { /* Send a smaller copy; never modify original. */ }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível abrir a foto.");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2560) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível reabrir a foto.");
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("Foto não pôde ser redimensionada para a IA.");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                throw new IOException("Falha ao preparar a imagem para o Gemini.");
            if (output.size() > INPUT_LIMIT) throw new IOException("Foto muito grande para envio.");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > max) throw new IOException("Arquivo acima do limite de envio.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Uri saveCopy(Context c, byte[] bytes, String mime) throws IOException {
        String extension = mime.equals("image/png") ? ".png" : mime.equals("image/webp") ? ".webp" : ".jpg";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "LUMA_AI_" + stamp + extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/LumaA17/IA");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Não foi possível criar a cópia no armazenamento.");
        try {
            try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Não foi possível escrever a cópia da IA.");
                out.write(bytes);
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(uri, published, null, null);
            return uri;
        } catch (Exception ex) {
            c.getContentResolver().delete(uri, null, null);
            throw new IOException("Não foi possível salvar a cópia da IA.", ex);
        }
    }
}
