package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Drop-in replacement for TypingDotsView.
 * Three dots morph between circle → rounded-square → diamond shapes
 * with translateY and a glow effect. 48dp × 20dp.
 */
public class CipherDotsView extends View {

    private static final int   DOT_COUNT      = 3;
    private static final int   DOT_DIAM_DP    = 10;
    private static final int   DOT_GAP_DP     = 8;
    private static final int   ANIM_DURATION  = 1600;
    private static final int   STAGGER_MS     = 200;

    private static final int COLOR_START = 0xFF7C6BFF;
    private static final int COLOR_END   = 0xFF9A81FF;

    private final float[]         dotFraction = new float[DOT_COUNT];
    private final ValueAnimator[] animators   = new ValueAnimator[DOT_COUNT];
    private final Paint           dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint           glowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF           rect        = new RectF();

    private float density;
    private float dotRadius;

    public CipherDotsView(Context ctx) { this(ctx, null); }
    public CipherDotsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }
    public CipherDotsView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        init(ctx);
    }

    private void init(Context ctx) {
        density   = ctx.getResources().getDisplayMetrics().density;
        dotRadius = (DOT_DIAM_DP / 2f) * density;

        glowPaint.setStyle(Paint.Style.FILL);
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    public void startDotAnimation() {
        stopDotAnimation();
        for (int i = 0; i < DOT_COUNT; i++) {
            final int idx = i;
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(ANIM_DURATION);
            va.setStartDelay(i * STAGGER_MS);
            va.setRepeatCount(ValueAnimator.INFINITE);
            va.setRepeatMode(ValueAnimator.REVERSE);
            va.setInterpolator(new LinearInterpolator());
            va.addUpdateListener(anim -> {
                dotFraction[idx] = (float) anim.getAnimatedValue();
                invalidate();
            });
            va.start();
            animators[i] = va;
        }
    }

    public void stopDotAnimation() {
        for (int i = 0; i < DOT_COUNT; i++) {
            if (animators[i] != null) {
                animators[i].cancel();
                animators[i] = null;
            }
            dotFraction[i] = 0f;
        }
        invalidate();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startDotAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDotAnimation();
        super.onDetachedFromWindow();
    }

    // ── Measure ────────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = (int) (48 * density + 0.5f);
        int h = (int) (20 * density + 0.5f);
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    // ── Draw ───────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        float gap    = DOT_GAP_DP * density;
        float diam   = dotRadius * 2f;
        float totalW = DOT_COUNT * diam + (DOT_COUNT - 1) * gap;
        float startX = (getWidth() - totalW) / 2f + dotRadius;
        float centerY = getHeight() / 2f;

        for (int i = 0; i < DOT_COUNT; i++) {
            float t = dotFraction[i]; // 0→1 forward, 1→0 reverse

            // Vertical translation: peak at t=0.5
            float tNorm  = t < 0.5f ? t : (1f - t);          // 0→0.5→0
            float transY = -tNorm * 10f * density;            // 0 → -5dp → 0

            // Scale: pulsate slightly
            float scaleF = 1f + tNorm * 0.6f;                 // 1→1.3→1

            // Corner radius morphing:
            //   t in [0.00, 0.25) → full circle (dotRadius)
            //   t in [0.25, 0.50) → circle→square (corner shrinks to 3dp)
            //   t in [0.50, 1.00] → stays at 3dp (already reversed by REVERSE mode)
            float corner;
            if (t < 0.25f) {
                corner = dotRadius;
            } else if (t < 0.5f) {
                float p = (t - 0.25f) * 4f;                   // 0→1
                corner  = dotRadius * (1f - p) + 3f * density * p;
            } else {
                corner = 3f * density;
            }

            // Color interpolation: start→end over first half
            float colorT = Math.min(1f, t * 2f);
            int color    = interpolateColor(COLOR_START, COLOR_END, colorT);

            float cx = startX + i * (diam + gap);
            float cy = centerY + transY;
            float r  = dotRadius * scaleF;

            // Glow layer (larger, semi-transparent)
            int glowAlpha = (int) (80 * tNorm * 2f);          // 0→80→0
            if (glowAlpha > 0) {
                glowPaint.setColor((color & 0x00FFFFFF) | (glowAlpha << 24));
                float gr = r + 4f * density;
                rect.set(cx - gr, cy - gr, cx + gr, cy + gr);
                canvas.drawRoundRect(rect, corner + 4f * density, corner + 4f * density, glowPaint);
            }

            // Main dot
            dotPaint.setColor(color);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawRoundRect(rect, corner, corner, dotPaint);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static int interpolateColor(int colorA, int colorB, float fraction) {
        int aA = (colorA >> 24) & 0xFF, aB = (colorB >> 24) & 0xFF;
        int rA = (colorA >> 16) & 0xFF, rB = (colorB >> 16) & 0xFF;
        int gA = (colorA >>  8) & 0xFF, gB = (colorB >>  8) & 0xFF;
        int bA =  colorA        & 0xFF, bB =  colorB        & 0xFF;
        int a = (int) (aA + (aB - aA) * fraction);
        int r = (int) (rA + (rB - rA) * fraction);
        int g = (int) (gA + (gB - gA) * fraction);
        int b = (int) (bA + (bB - bA) * fraction);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
