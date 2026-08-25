package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.duoshield.app.util.MotionBudget;

public class FingerprintScanView extends View {

    private static final float[][] RIDGE_RADII_DP = {
        {48f, 52f}, {38f, 42f}, {28f, 32f}, {19f, 22f}, {10f, 13f}
    };

    private final Paint ridgePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Pre-allocated — no per-frame allocation
    private final RectF[] ridgeOvals  = new RectF[RIDGE_RADII_DP.length];
    private final RectF   glowRect    = new RectF();

    // Gradient rebuilt only in onSizeChanged
    private LinearGradient cachedGradient;
    private float           cachedGlowTop = -1f;

    private ValueAnimator scanAnimator;
    private float scanFraction = 0f;
    private float dp;

    // Frame-pacing (see MotionBudget): redraws capped on LOW-tier devices.
    private long frameIntervalMs = 0L;
    private long lastFrameUptime = 0L;

    public FingerprintScanView(Context context) { super(context); init(); }
    public FingerprintScanView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public FingerprintScanView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        dp = getContext().getResources().getDisplayMetrics().density;

        ridgePaint.setStyle(Paint.Style.STROKE);
        ridgePaint.setStrokeWidth(2f * dp);
        ridgePaint.setColor(0xFF7C6BFF);
        ridgePaint.setAlpha(180);

        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(2.5f * dp);
        bracketPaint.setColor(0xFF7C6BFF);

        scanLinePaint.setStyle(Paint.Style.STROKE);
        scanLinePaint.setStrokeWidth(1.5f * dp);
        scanLinePaint.setColor(0xFF9A81FF);

        scanGlowPaint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < ridgeOvals.length; i++) ridgeOvals[i] = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f, cy = h / 2f;
        for (int i = 0; i < RIDGE_RADII_DP.length; i++) {
            float rx = RIDGE_RADII_DP[i][0] * dp, ry = RIDGE_RADII_DP[i][1] * dp;
            ridgeOvals[i].set(cx - rx, cy - ry, cx + rx, cy + ry);
        }
        // Rebuild gradient with a placeholder position — updated cheaply in onDraw
        cachedGlowTop = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        float cx = width / 2f, cy = height / 2f;

        // Ridges — no allocation
        for (RectF oval : ridgeOvals) canvas.drawOval(oval, ridgePaint);

        // Corner brackets
        float bl = 12f * dp, bi = 8f * dp;
        canvas.drawLine(bi, bi, bi + bl, bi, bracketPaint);
        canvas.drawLine(bi, bi, bi, bi + bl, bracketPaint);
        canvas.drawLine(width - bi, bi, width - bi - bl, bi, bracketPaint);
        canvas.drawLine(width - bi, bi, width - bi, bi + bl, bracketPaint);
        canvas.drawLine(bi, height - bi, bi + bl, height - bi, bracketPaint);
        canvas.drawLine(bi, height - bi, bi, height - bi - bl, bracketPaint);
        canvas.drawLine(width - bi, height - bi, width - bi - bl, height - bi, bracketPaint);
        canvas.drawLine(width - bi, height - bi, width - bi, height - bi - bl, bracketPaint);

        // Scan line + glow
        float scanY   = scanFraction * height;
        float glowH   = 20f * dp;
        float glowTop = Math.max(0f, scanY - glowH);
        int   alpha   = (int) (Math.sin(scanFraction * Math.PI) * 220);
        if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;

        // Rebuild gradient only when glowTop shifts by >1px (avoids per-frame alloc)
        if (Math.abs(glowTop - cachedGlowTop) > 1f) {
            cachedGradient = new LinearGradient(
                0, glowTop, 0, scanY,
                new int[]{Color.TRANSPARENT, 0x407C6BFF, Color.TRANSPARENT},
                new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP);
            scanGlowPaint.setShader(cachedGradient);
            cachedGlowTop = glowTop;
        }
        glowRect.set(0, glowTop, width, scanY);
        scanGlowPaint.setAlpha(alpha);
        canvas.drawRect(glowRect, scanGlowPaint);

        scanLinePaint.setAlpha(alpha);
        canvas.drawLine(0, scanY, width, scanY, scanLinePaint);
    }

    public void startScan() {
        if (scanAnimator != null && scanAnimator.isRunning()) return;
        // Respect the OS "remove animations" setting: draw one static frame, no loop.
        if (MotionBudget.staticOnly(getContext())) { invalidate(); return; }
        frameIntervalMs = MotionBudget.frameIntervalMs(getContext());
        lastFrameUptime = 0L;
        scanAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanAnimator.setDuration(2200);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.addUpdateListener(a -> {
            scanFraction = (float) a.getAnimatedValue();
            if (MotionBudget.shouldDrawFrame(frameIntervalMs, lastFrameUptime)) {
                lastFrameUptime = SystemClock.uptimeMillis();
                invalidate();
            }
        });
        scanAnimator.start();
    }

    public void stopScan() {
        if (scanAnimator != null) { scanAnimator.cancel(); scanAnimator = null; }
        scanFraction = 0f; invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startScan(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stopScan(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize((int)(120*dp), wSpec), resolveSize((int)(140*dp), hSpec));
    }
}
