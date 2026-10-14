package com.ib2s.lumaa17;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import androidx.camera.view.PreviewView;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Samples just 19,200 preview pixels. No third camera stream, full-frame copies, or saved media. */
final class PreviewMeter {
    private final PreviewView preview;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::sample;
    private int generation;
    private boolean running;
    private BooleanSupplier allowed;
    private LongSupplier interval;
    private Consumer<ExposureAdvisor.Sample> consumer;

    PreviewMeter(PreviewView preview) { this.preview = preview; }

    void start(BooleanSupplier allowed, LongSupplier interval, Consumer<ExposureAdvisor.Sample> consumer) {
        stop(); this.allowed = allowed; this.interval = interval; this.consumer = consumer; running = true;
        schedule(800);
    }

    void stop() { running = false; generation++; handler.removeCallbacks(poll); }

    private void schedule(long delay) {
        if (running) handler.postDelayed(poll, delay);
    }

    private void sample() {
        if (!running) return;
        if (!allowed.getAsBoolean() || !preview.isShown()) { schedule(interval.getAsLong()); return; }
        View source = surface(preview);
        if (source == null || source.getWidth() == 0 || source.getHeight() == 0) {
            schedule(interval.getAsLong()); return;
        }
        int epoch = generation;
        Bitmap bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888);
        try {
            if (source instanceof TextureView texture) {
                if (!texture.isAvailable() || texture.getBitmap(bitmap) == null) finish(epoch, bitmap, false);
                else finish(epoch, bitmap, true);
            } else if (source instanceof SurfaceView view && view.getHolder().getSurface().isValid()) {
                PixelCopy.request(view, bitmap, result -> finish(epoch, bitmap, result == PixelCopy.SUCCESS), handler);
            } else finish(epoch, bitmap, false);
        } catch (RuntimeException ignored) { finish(epoch, bitmap, false); }
    }

    private void finish(int epoch, Bitmap bitmap, boolean success) {
        try {
            if (running && epoch == generation && success && allowed.getAsBoolean()) {
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                consumer.accept(ExposureAdvisor.measure(pixels, bitmap.getWidth(), bitmap.getHeight()));
            }
        } finally {
            bitmap.recycle();
            if (running && epoch == generation) schedule(interval.getAsLong());
        }
    }

    private View surface(View view) {
        if (view instanceof TextureView || view instanceof SurfaceView) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = surface(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
