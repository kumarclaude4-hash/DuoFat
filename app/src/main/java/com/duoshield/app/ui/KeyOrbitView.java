package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.duoshield.app.util.MotionBudget;

public class KeyOrbitView extends View {

    private float rotationDeg = 0f;
    private ValueAnimator orbitAnim;

    // Frame-pacing: on LOW-tier devices redraws are capped (see MotionBudget); the animator still
    // advances rotationDeg every tick, only the GPU-bound invalidate() is throttled.
    private long frameIntervalMs = 0L;
    private long lastFrameUptime = 0L;

    private final Paint trackPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaintA     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaintB     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Pre-allocated RectFs — set in onSizeChanged
    private final RectF shackleRect = new RectF();
    private final RectF bodyRect    = new RectF();

    private float dp;

    public KeyOrbitView(Context context) { this(context, null); }
    public KeyOrbitView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public KeyOrbitView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        dp = context.getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(1.5f * dp);
        trackPaint.setColor(0xFF3A3548);
        trackPaint.setPathEffect(new DashPathEffect(new float[]{8f * dp, 6f * dp}, 0));

        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(0xFF2D2938);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f * dp);
        borderPaint.setColor(0xFF3A3548);

        dotPaintA.setStyle(Paint.Style.FILL);
        dotPaintA.setColor(0xFF7C6BFF);

        dotPaintB.setStyle(Paint.Style.FILL);
        dotPaintB.setColor(0xFF9A81FF);

        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(2f * dp);
        lockPaint.setColor(0xFF7C6BFF);
        lockPaint.setStrokeCap(Paint.Cap.ROUND);

        lockFillPaint.setStyle(Paint.Style.FILL);
        lockFillPaint.setColor(0xFF2D2938);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f, cy = h / 2f;
        shackleRect.set(cx - 6f*dp, cy - 14f*dp, cx + 6f*dp, cy - 4f*dp);
        bodyRect.set(cx - 7f*dp, cy - 6f*dp, cx + 7f*dp, cy + 6f*dp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float orbitR = 38f * dp, dotR = 5.5f * dp, centerR = 18f * dp;

        canvas.drawCircle(cx, cy, orbitR, trackPaint);
        canvas.drawCircle(cx, cy, centerR, centerPaint);
        canvas.drawCircle(cx, cy, centerR, borderPaint);

        // Lock icon — pre-allocated RectFs
        canvas.drawArc(shackleRect, 180, 180, false, lockPaint);
        canvas.drawRoundRect(bodyRect, 3f*dp, 3f*dp, lockFillPaint);
        canvas.drawRoundRect(bodyRect, 3f*dp, 3f*dp, lockPaint);

        // Orbiting dots
        double radA = Math.toRadians(rotationDeg);
        canvas.drawCircle(cx + (float)(orbitR * Math.cos(radA)),
                          cy + (float)(orbitR * Math.sin(radA)), dotR, dotPaintA);
        double radB = Math.toRadians(rotationDeg + 180);
        canvas.drawCircle(cx + (float)(orbitR * Math.cos(radB)),
                          cy + (float)(orbitR * Math.sin(radB)), dotR, dotPaintB);
    }

    public void startOrbit() {
        if (orbitAnim != null && orbitAnim.isRunning()) return;
        // Respect the OS "remove animations" setting: draw one static frame, no loop.
        if (MotionBudget.staticOnly(getContext())) { invalidate(); return; }
        frameIntervalMs = MotionBudget.frameIntervalMs(getContext());
        lastFrameUptime = 0L;
        orbitAnim = ValueAnimator.ofFloat(0f, 360f);
        orbitAnim.setDuration(2000);
        orbitAnim.setRepeatCount(ValueAnimator.INFINITE);
        orbitAnim.setRepeatMode(ValueAnimator.RESTART);
        orbitAnim.setInterpolator(new LinearInterpolator());
        orbitAnim.addUpdateListener(a -> {
            rotationDeg = (float) a.getAnimatedValue();
            if (MotionBudget.shouldDrawFrame(frameIntervalMs, lastFrameUptime)) {
                lastFrameUptime = SystemClock.uptimeMillis();
                invalidate();
            }
        });
        orbitAnim.start();
    }

    public void stopOrbit() {
        if (orbitAnim != null) { orbitAnim.cancel(); orbitAnim = null; }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startOrbit(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stopOrbit(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int s = Math.round(100f * dp);
        setMeasuredDimension(resolveSize(s, wSpec), resolveSize(s, hSpec));
    }
}
