package com.ib2s.lumaa17;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Lightweight composition guide. Does not read or copy preview frames. */
public final class CameraOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public boolean grid = true, showLevel = true, locked;
    private float roll, focusX = -1, focusY;
    private long focusUntil;
    private String countdown = "";
    private final float density;

    public CameraOverlay(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void roll(float value) { roll = value; if (showLevel) invalidate(); }
    public void focus(float x, float y, boolean lock) {
        focusX = x; focusY = y; locked = lock;
        focusUntil = SystemClock.elapsedRealtime() + 1800; invalidate();
        postDelayed(this::invalidate, 1850);
    }
    public void countdown(String value) { countdown = value; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
        if (grid) {
            paint.setColor(0x66FFFFFF);
            for (int i=1;i<3;i++) {
                canvas.drawLine(w*i/3, 0, w*i/3, h, paint);
                canvas.drawLine(0, h*i/3, w, h*i/3, paint);
            }
        }
        if (showLevel) {
            canvas.save(); canvas.translate(w/2, h/2);
            paint.setColor(Math.abs(roll) < 2 ? 0xFFE8C97E : 0xAAFFFFFF);
            paint.setStrokeWidth(2*density);
            canvas.drawLine(-44*density, 0, -30*density, 0, paint);
            canvas.drawLine(30*density, 0, 44*density, 0, paint);
            canvas.rotate(-roll);
            canvas.drawLine(-25*density, 0, 25*density, 0, paint);
            canvas.drawCircle(0, 0, 3*density, paint); canvas.restore();
        }
        if (focusX >= 0 && (locked || SystemClock.elapsedRealtime() < focusUntil)) {
            paint.setColor(0xFFE8C97E); paint.setStrokeWidth(1.5f*density);
            float r = 30*density;
            canvas.drawRoundRect(new RectF(focusX-r, focusY-r, focusX+r, focusY+r), 7*density, 7*density, paint);
        }
        if (!countdown.isEmpty()) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            paint.setTextSize(76*density); paint.setTextAlign(Paint.Align.CENTER);
            paint.setShadowLayer(8*density, 0, 2*density, 0xFF000000);
            canvas.drawText(countdown, w/2, h/2+25*density, paint); paint.clearShadowLayer();
        }
    }
}
