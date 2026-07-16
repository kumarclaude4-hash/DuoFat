package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class MatrixRainView extends View {

    private static final char[] GLYPHS = "アイウエ01ΩΣ#βΔ≡Ψ".toCharArray();

    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float density;

    // Single-char buffer — reused every draw, no per-frame allocation
    private final char[] glyphBuf = new char[1];

    private float[] dropY;
    private int cols;
    private boolean sized = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            invalidate();
            handler.postDelayed(this, 50);
        }
    };

    public MatrixRainView(Context context) { this(context, null); }
    public MatrixRainView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public MatrixRainView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        textPaint.setTextSize(11f * density);
        textPaint.setTypeface(Typeface.MONOSPACE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        cols = Math.max(1, (int)(w / (14f * density)));
        dropY = new float[cols];
        for (int i = 0; i < cols; i++) dropY[i] = -random.nextFloat() * h;
        sized = true;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int wMode = MeasureSpec.getMode(wSpec), hMode = MeasureSpec.getMode(hSpec);
        int w = (wMode == MeasureSpec.EXACTLY || wMode == MeasureSpec.AT_MOST)
                ? MeasureSpec.getSize(wSpec) : (int)(360f * density);
        int h = (hMode == MeasureSpec.EXACTLY || hMode == MeasureSpec.AT_MOST)
                ? MeasureSpec.getSize(hSpec) : (int)(640f * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!sized || dropY == null) return;
        float cellW = 14f * density, cellH = 14f * density;
        int height = getHeight();

        for (int col = 0; col < cols; col++) {
            float x = col * cellW;

            // Trailing glyph — accent purple, dimmer
            glyphBuf[0] = GLYPHS[random.nextInt(GLYPHS.length)];
            textPaint.setColor(0xFF9A81FF);
            textPaint.setAlpha(120);
            canvas.drawText(glyphBuf, 0, 1, x, dropY[col] - cellH, textPaint);

            // Head glyph — white, bright
            glyphBuf[0] = GLYPHS[random.nextInt(GLYPHS.length)];
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setAlpha(230);
            canvas.drawText(glyphBuf, 0, 1, x, dropY[col], textPaint);

            dropY[col] += (3f + random.nextFloat() * 4f) * density;
            if (dropY[col] > height + 20f * density && random.nextFloat() > 0.96f)
                dropY[col] = -20f * density;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tickRunnable); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(tickRunnable); }
}
