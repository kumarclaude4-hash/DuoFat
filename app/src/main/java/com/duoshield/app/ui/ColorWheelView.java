package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * An HSV colour-wheel view.
 *
 * <ul>
 *   <li>Hue = angle around the circle (red at 3 o'clock)</li>
 *   <li>Saturation = distance from centre (0 = white, 1 = full colour)</li>
 *   <li>Brightness = controlled externally via {@link #setBrightness(float)}</li>
 * </ul>
 *
 * The wheel is rendered into a 256×256 bitmap and scaled to the view's actual
 * size, so the build cost is constant (~0 ms on modern hardware) regardless of
 * screen density.
 */
public class ColorWheelView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private static final int BMP_SIZE = 256;

    private Bitmap   wheelBitmap;
    private Paint    bitmapPaint;
    private Paint    selectorFillPaint;
    private Paint    selectorStrokePaint;

    /** Current selector position in view coordinates. */
    private float selectorX, selectorY;
    /** Centre and radius of the drawn circle (view coordinates). */
    private float cx, cy, radius;
    /** Whether the view has been laid out at least once. */
    private boolean laid = false;

    private float brightness = 1f;
    private int   currentColor = Color.RED;

    private OnColorChangedListener listener;

    // ── Constructors ────────────────────────────────────────────────────────

    public ColorWheelView(Context ctx)                          { super(ctx); init(); }
    public ColorWheelView(Context ctx, AttributeSet a)          { super(ctx, a); init(); }
    public ColorWheelView(Context ctx, AttributeSet a, int d)   { super(ctx, a, d); init(); }

    private void init() {
        bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        selectorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectorFillPaint.setStyle(Paint.Style.FILL);

        selectorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectorStrokePaint.setStyle(Paint.Style.STROKE);
        selectorStrokePaint.setStrokeWidth(2.5f);
    }

    // ── Layout / bitmap ─────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float size = Math.min(w, h);
        cx     = w / 2f;
        cy     = h / 2f;
        radius = size / 2f - 6f;
        rebuildWheel();
        if (!laid) {
            // Place selector at the position matching the initial colour.
            placed = false; // will be set in setColor below
            setColorInternal(currentColor, false);
            laid = true;
        }
    }

    private boolean placed = false;

    /**
     * Rebuilds the 256×256 HSV wheel bitmap for the current {@link #brightness}.
     */
    private void rebuildWheel() {
        int[] pixels = new int[BMP_SIZE * BMP_SIZE];
        float center = BMP_SIZE / 2f;
        float[] hsv = new float[3];
        for (int y = 0; y < BMP_SIZE; y++) {
            for (int x = 0; x < BMP_SIZE; x++) {
                float dx = x - center;
                float dy = y - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > center) {
                    pixels[y * BMP_SIZE + x] = 0; // transparent
                } else {
                    hsv[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
                    hsv[1] = Math.min(dist / center, 1f);
                    hsv[2] = brightness;
                    pixels[y * BMP_SIZE + x] = Color.HSVToColor(hsv);
                }
            }
        }
        if (wheelBitmap == null) {
            wheelBitmap = Bitmap.createBitmap(BMP_SIZE, BMP_SIZE, Bitmap.Config.ARGB_8888);
        }
        wheelBitmap.setPixels(pixels, 0, BMP_SIZE, 0, 0, BMP_SIZE, BMP_SIZE);
        invalidate();
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheelBitmap == null || radius <= 0) return;

        // Scale the 256-px bitmap to fill the drawn circle.
        RectF dst = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawBitmap(wheelBitmap, null, dst, bitmapPaint);

        // Selector ring: white fill + dark stroke for visibility on any colour.
        float sr = 11f;
        selectorFillPaint.setColor(currentColor);
        selectorStrokePaint.setColor(isDark(currentColor) ? 0xCCFFFFFF : 0x99000000);
        selectorStrokePaint.setStrokeWidth(3f);

        canvas.drawCircle(selectorX, selectorY, sr, selectorFillPaint);
        canvas.drawCircle(selectorX, selectorY, sr, selectorStrokePaint);

        // Outer ring on the indicator in the contrasting colour
        Paint outerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerRing.setStyle(Paint.Style.STROKE);
        outerRing.setStrokeWidth(1.5f);
        outerRing.setColor(0xFFFFFFFF);
        canvas.drawCircle(selectorX, selectorY, sr + 2f, outerRing);
    }

    private boolean isDark(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b = color         & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128;
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_CANCEL) return true;

        float x = event.getX();
        float y = event.getY();
        float dx = x - cx;
        float dy = y - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        // Clamp to wheel edge.
        if (dist > radius) {
            float angle = (float) Math.atan2(dy, dx);
            x = cx + radius * (float) Math.cos(angle);
            y = cy + radius * (float) Math.sin(angle);
            dist = radius;
        }

        selectorX = x;
        selectorY = y;

        float hue = (float) ((Math.toDegrees(Math.atan2(y - cy, x - cx)) + 360) % 360);
        float sat = Math.min(dist / radius, 1f);
        currentColor = Color.HSVToColor(new float[]{hue, sat, brightness});

        if (listener != null) listener.onColorChanged(currentColor);
        invalidate();
        return true;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setBrightness(float b) {
        brightness = Math.max(0.01f, Math.min(1f, b));
        rebuildWheel();
        // Recompute current colour with new brightness.
        float dx = selectorX - cx;
        float dy = selectorY - cy;
        float dist = Math.min((float) Math.sqrt(dx * dx + dy * dy), radius);
        float hue  = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
        float sat  = (radius > 0) ? dist / radius : 0f;
        currentColor = Color.HSVToColor(new float[]{hue, sat, brightness});
        if (listener != null) listener.onColorChanged(currentColor);
        invalidate();
    }

    public float getBrightness() { return brightness; }

    public void setColor(int color) {
        setColorInternal(color, true);
    }

    private void setColorInternal(int color, boolean notify) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        brightness   = Math.max(0.01f, hsv[2]);
        currentColor = color;
        if (radius > 0) {
            double rad = Math.toRadians(hsv[0]);
            selectorX = cx + (float) (Math.cos(rad) * hsv[1] * radius);
            selectorY = cy + (float) (Math.sin(rad) * hsv[1] * radius);
            placed = true;
            rebuildWheel();
        }
        if (notify && listener != null) listener.onColorChanged(currentColor);
    }

    public int getColor() { return currentColor; }

    public void setOnColorChangedListener(OnColorChangedListener l) { listener = l; }
}
