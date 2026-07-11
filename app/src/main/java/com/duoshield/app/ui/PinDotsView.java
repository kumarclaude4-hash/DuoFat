package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

import com.duoshield.app.R;

/**
 * Purely visual PIN-entry indicator: a row of segmented dots that fill in as
 * digits are typed. It has no text focus or input of its own — the lock
 * screen keeps a zero-size, invisible {@code EditText} to capture keyboard
 * input and calls {@link #setFilledCount(int)} on every change.
 *
 * <p>Supports 4–6 dots since app PINs are variable length (see PinManager).
 * The dot count shown is always {@link #maxCount}; entered digits fill dots
 * left to right.
 */
public class PinDotsView extends View {

    private static final int DEFAULT_MAX = 6;

    private int maxCount = DEFAULT_MAX;
    private int filledCount = 0;
    private float dotRadiusPx;
    private float dotSpacingPx;
    private int colorFilled;
    private int colorEmpty;

    private final Paint paint = new Paint();

    public PinDotsView(Context context) {
        super(context);
        init(context);
    }

    public PinDotsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        paint.setAntiAlias(true);
        float density = context.getResources().getDisplayMetrics().density;
        dotRadiusPx = 7f * density;
        dotSpacingPx = 22f * density;
        colorFilled = getResources().getColor(R.color.ds_accent, null);
        colorEmpty = getResources().getColor(R.color.ds_divider, null);
        setContentDescription("PIN entry progress");
    }

    /** Sets how many dot slots to render (clamped 4–6). */
    public void setMaxCount(int max) {
        this.maxCount = Math.max(4, Math.min(6, max));
        requestLayout();
        invalidate();
    }

    /** Sets how many of the dots should appear filled; triggers a brief pulse on the newest dot. */
    public void setFilledCount(int count) {
        boolean grew = count > filledCount;
        filledCount = Math.max(0, Math.min(maxCount, count));
        if (grew) {
            ValueAnimator pulse = ValueAnimator.ofFloat(1.3f, 1f);
            pulse.setDuration(140);
            pulse.addUpdateListener(a -> {
                dotRadiusPx = 7f * getResources().getDisplayMetrics().density * (float) a.getAnimatedValue();
                invalidate();
            });
            pulse.start();
        } else {
            invalidate();
        }
    }

    public int getFilledCount() {
        return filledCount;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) (maxCount * dotSpacingPx);
        int desiredHeight = (int) (dotRadiusPx * 4);
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float totalWidth = maxCount * dotSpacingPx;
        float startX = (getWidth() - totalWidth) / 2f + dotSpacingPx / 2f;
        float centerY = getHeight() / 2f;

        for (int i = 0; i < maxCount; i++) {
            float cx = startX + i * dotSpacingPx;
            boolean filled = i < filledCount;
            paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(filled ? colorFilled : colorEmpty);
            float r = filled ? (7f * getResources().getDisplayMetrics().density) : (7f * getResources().getDisplayMetrics().density) - 1f;
            canvas.drawCircle(cx, centerY, r, paint);
        }
    }
}
