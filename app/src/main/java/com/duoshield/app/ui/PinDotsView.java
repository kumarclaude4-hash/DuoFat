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
 * Purely visual PIN-entry indicator: dots appear one by one as digits are
 * typed. It has no text focus or input of its own — the lock screen keeps a
 * zero-size, invisible {@code EditText} to capture keyboard input and calls
 * {@link #setFilledCount(int)} on every change.
 *
 * <p>Only entered digits are drawn — there are deliberately no empty
 * placeholder slots, so an onlooker cannot read the PIN's length off the
 * screen. The view still reserves the width of {@link #SLOT_CAPACITY} dots so
 * the row stays centred and the layout never shifts as digits are added, and
 * that reserved width is identical for every PIN length.
 */
public class PinDotsView extends View {

    /** Widest PIN the app allows; used only to reserve a constant view size. */
    private static final int SLOT_CAPACITY = 6;

    private int maxCount = SLOT_CAPACITY;
    private int filledCount = 0;
    private float dotRadiusPx;
    private float dotSpacingPx;
    private float pulseScale = 1f;
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

    /** Override filled/empty dot colours (call before the view is shown). */
    public void setColors(int filledColor, int emptyColor) {
        this.colorFilled = filledColor;
        this.colorEmpty  = emptyColor;
        invalidate();
    }

    /**
     * Sets the upper bound on how many dots can be drawn (clamped 4–6).
     *
     * <p>This no longer affects the view's appearance at rest — nothing is
     * drawn until a digit is entered — it only caps the visible dot count.
     * The reserved size stays at {@link #SLOT_CAPACITY} regardless, so the
     * PIN length is not observable from the layout.
     */
    public void setMaxCount(int max) {
        this.maxCount = Math.max(4, Math.min(SLOT_CAPACITY, max));
        invalidate();
    }

    /** Sets how many dots are shown; triggers a brief pulse on the newest dot. */
    public void setFilledCount(int count) {
        boolean grew = count > filledCount;
        filledCount = Math.max(0, Math.min(maxCount, count));
        if (grew) {
            ValueAnimator pulse = ValueAnimator.ofFloat(1.3f, 1f);
            pulse.setDuration(140);
            pulse.addUpdateListener(a -> {
                pulseScale = (float) a.getAnimatedValue();
                invalidate();
            });
            pulse.start();
        } else {
            pulseScale = 1f;
            invalidate();
        }
    }

    public int getFilledCount() {
        return filledCount;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Always reserve room for the widest allowed PIN so the row keeps a
        // constant footprint: the measured width must not hint at pinLength.
        int desiredWidth = (int) (SLOT_CAPACITY * dotSpacingPx);
        int desiredHeight = (int) (dotRadiusPx * 4);
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (filledCount <= 0) return;

        // Entered dots are centred as a group, so the row grows outward from
        // the middle instead of revealing how many slots are still unfilled.
        float baseRadius = 7f * getResources().getDisplayMetrics().density;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float startX = centerX - ((filledCount - 1) * dotSpacingPx) / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorFilled);

        for (int i = 0; i < filledCount; i++) {
            float cx = startX + i * dotSpacingPx;
            // Only the most recent dot pulses as it appears.
            float r = (i == filledCount - 1) ? baseRadius * pulseScale : baseRadius;
            canvas.drawCircle(cx, centerY, r, paint);
        }
    }
}
