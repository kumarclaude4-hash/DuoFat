package com.duoshield.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Three-dot animated typing indicator.
 * Dots bounce up/down in a staggered wave at 48dp wide × 18dp tall.
 * Call startAnimation() when shown, stopAnimation() when hidden.
 */
public class TypingDotsView extends View {

    private static final int DOT_COUNT  = 3;
    private static final int DOT_RADIUS_DP = 4;
    private static final int DOT_GAP_DP    = 7;
    private static final int ANIM_DURATION = 400;
    private static final int STAGGER_MS    = 130;
    private static final float BOUNCE_DP   = 5f;

    private final Paint  paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] dotY = new float[DOT_COUNT];
    private AnimatorSet  animatorSet;
    private float        dotRadius;
    private float        bounce;
    private float        centerY;

    public TypingDotsView(Context ctx) { this(ctx, null); }
    public TypingDotsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float density = ctx.getResources().getDisplayMetrics().density;
        dotRadius = DOT_RADIUS_DP * density;
        bounce    = BOUNCE_DP * density;
        paint.setColor(0xFF9A81FF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        centerY = h / 2f;
        for (int i = 0; i < DOT_COUNT; i++) dotY[i] = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density    = getResources().getDisplayMetrics().density;
        float gap        = DOT_GAP_DP * density;
        float totalW     = DOT_COUNT * (dotRadius * 2) + (DOT_COUNT - 1) * gap;
        float startX     = (getWidth() - totalW) / 2f + dotRadius;

        for (int i = 0; i < DOT_COUNT; i++) {
            float x = startX + i * (dotRadius * 2 + gap);
            canvas.drawCircle(x, dotY[i], dotRadius, paint);
        }
    }

    /** Call whenever the view becomes visible. */
    public void startDotAnimation() {
        stopDotAnimation();
        AnimatorSet set = new AnimatorSet();
        Animator[] anims = new Animator[DOT_COUNT];
        for (int i = 0; i < DOT_COUNT; i++) {
            final int idx = i;
            ObjectAnimator up = ObjectAnimator.ofFloat(this, "dot" + i, centerY, centerY - bounce);
            up.setDuration(ANIM_DURATION);
            up.setInterpolator(new AccelerateDecelerateInterpolator());
            up.setStartDelay(i * STAGGER_MS);
            up.addUpdateListener(a -> {
                dotY[idx] = (float) a.getAnimatedValue();
                invalidate();
            });
            up.setRepeatCount(ObjectAnimator.INFINITE);
            up.setRepeatMode(ObjectAnimator.REVERSE);
            anims[i] = up;
        }
        set.playTogether(anims);
        set.start();
        animatorSet = set;
    }

    /** Call whenever the view is hidden. */
    public void stopDotAnimation() {
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
        for (int i = 0; i < DOT_COUNT; i++) dotY[i] = centerY;
        invalidate();
    }

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

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        int w = (int) ((DOT_COUNT * DOT_RADIUS_DP * 2 + (DOT_COUNT - 1) * DOT_GAP_DP) * density + 0.5f);
        int h = (int) (20 * density + 0.5f);
        setMeasuredDimension(
            resolveSize(w, widthSpec),
            resolveSize(h, heightSpec)
        );
    }
}
