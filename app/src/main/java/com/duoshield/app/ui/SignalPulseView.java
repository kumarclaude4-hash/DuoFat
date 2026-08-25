package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.duoshield.app.util.MotionBudget;

public class SignalPulseView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] ringFrac = {0f, 0f, 0f};
    private final ValueAnimator[] animators = new ValueAnimator[3];
    private final float density;

    // Frame-pacing shared across all three ring animators (see MotionBudget). Capped on LOW.
    private long frameIntervalMs = 0L;
    private long lastFrameUptime = 0L;

    public SignalPulseView(Context context) {
        this(context, null);
    }

    public SignalPulseView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalPulseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;

        int[] delays = {0, 800, 1600};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2400);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.setStartDelay(delays[i]);
            anim.addUpdateListener(animation -> {
                ringFrac[idx] = (float) animation.getAnimatedValue();
                if (MotionBudget.shouldDrawFrame(frameIntervalMs, lastFrameUptime)) {
                    lastFrameUptime = SystemClock.uptimeMillis();
                    invalidate();
                }
            });
            animators[i] = anim;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (140f * density);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(0xFF7C6BFF);

        for (int i = 0; i < 3; i++) {
            float r = (20f + ringFrac[i] * 50f) * density;
            int alpha = (int) ((1f - ringFrac[i]) * 200);
            paint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, r, paint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Respect the OS "remove animations" setting: leave the rings static.
        if (MotionBudget.staticOnly(getContext())) return;
        frameIntervalMs = MotionBudget.frameIntervalMs(getContext());
        lastFrameUptime = 0L;
        for (ValueAnimator anim : animators) {
            anim.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (ValueAnimator anim : animators) {
            anim.cancel();
        }
    }
}
