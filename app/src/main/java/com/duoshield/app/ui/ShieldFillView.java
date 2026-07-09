package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Custom View: shield outline that fills bottom-to-top, then a checkmark draws in.
 */
public class ShieldFillView extends View {

    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path shieldPath = new Path();
    private Path checkPath  = new Path();

    private float fillFraction   = 0f;
    private float lastCheckFrac  = -1f;
    private DashPathEffect cachedDashEffect;

    public ShieldFillView(Context context) {
        super(context);
        init();
    }

    public ShieldFillView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShieldFillView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float strokeWidth = dp(3f);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(strokeWidth);
        outlinePaint.setColor(0xFF7C6BFF);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);

        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(strokeWidth);
        checkPaint.setColor(0xFFFFFFFF);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Build shield path from normalised coords scaled to view size
        shieldPath.reset();
        shieldPath.moveTo(0.5f * w, 0.05f * h);
        shieldPath.lineTo(0.9f * w, 0.18f * h);
        shieldPath.lineTo(0.9f * w, 0.52f * h);
        shieldPath.quadTo(0.9f * w, 0.82f * h, 0.5f * w, 0.97f * h);
        shieldPath.quadTo(0.1f * w, 0.82f * h, 0.1f * w, 0.52f * h);
        shieldPath.lineTo(0.1f * w, 0.18f * h);
        shieldPath.close();

        // Build checkmark path
        checkPath.reset();
        checkPath.moveTo(0.33f * w, 0.52f * h);
        checkPath.lineTo(0.45f * w, 0.64f * h);
        checkPath.lineTo(0.67f * w, 0.42f * h);

        // Rebuild gradient shader for new size
        fillPaint.setShader(new LinearGradient(
                0, h, 0, 0,
                0xFF7C6BFF, 0xFF9A81FF,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Fill — clip from bottom up by fillFraction
        canvas.save();
        canvas.clipRect(0, h * (1f - fillFraction), w, h);
        canvas.drawPath(shieldPath, fillPaint);
        canvas.restore();

        // Outline always drawn on top
        canvas.drawPath(shieldPath, outlinePaint);

        // Checkmark animates in when fill > 0.8
        if (fillFraction > 0.8f) {
            float checkFrac = (fillFraction - 0.8f) / 0.2f;
            // Only rebuild DashPathEffect when checkFrac changes meaningfully
            if (cachedDashEffect == null || Math.abs(checkFrac - lastCheckFrac) > 0.005f) {
                cachedDashEffect = new DashPathEffect(
                        new float[]{1000f, 1000f}, 1000f * (1f - checkFrac));
                lastCheckFrac = checkFrac;
            }
            checkPaint.setPathEffect(cachedDashEffect);
            canvas.drawPath(checkPath, checkPaint);
        }
    }

    /** Set fill progress 0.0 → 1.0. */
    public void setProgress(float p) {
        fillFraction = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) dp(80f);
        int h = (int) dp(90f);
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
