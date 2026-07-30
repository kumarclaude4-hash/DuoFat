package com.duoshield.app.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * A {@link LinearLayout} that actually enforces {@code android:maxWidth}.
 *
 * <p>Android's built-in {@code android:maxWidth} attribute is parsed and stored
 * by the framework but LinearLayout never reads it during {@code onMeasure}, so
 * long messages stretch the bubble to the full parent width.  This subclass
 * overrides {@code onMeasure} to clamp the width spec before delegating to the
 * super implementation, producing correct wrap-content behaviour with an upper
 * bound.
 *
 * <p>The limit can also be changed at runtime via {@link #setMaxWidth(int)},
 * which is used by {@link MessageAdapter} to enforce 80% of the screen width
 * independent of screen density.
 */
public final class MaxWidthLinearLayout extends LinearLayout {

    private int maxWidthPx = Integer.MAX_VALUE;

    public MaxWidthLinearLayout(Context ctx) {
        super(ctx);
    }

    public MaxWidthLinearLayout(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        readAttr(ctx, attrs);
    }

    public MaxWidthLinearLayout(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        readAttr(ctx, attrs);
    }

    private void readAttr(Context ctx, AttributeSet attrs) {
        // android.R.attr.maxWidth is a plain dimension attribute shared by many widgets.
        int[] attrIds = { android.R.attr.maxWidth };
        TypedArray ta = ctx.obtainStyledAttributes(attrs, attrIds);
        int xmlMax = ta.getDimensionPixelSize(0, Integer.MAX_VALUE);
        if (xmlMax != Integer.MAX_VALUE) maxWidthPx = xmlMax;
        ta.recycle();
    }

    /** Override the maximum width at runtime (e.g. 80 % of the screen width). */
    public void setMaxWidth(int px) {
        if (maxWidthPx != px) {
            maxWidthPx = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxWidthPx != Integer.MAX_VALUE) {
            int specSize = MeasureSpec.getSize(widthMeasureSpec);
            if (specSize > maxWidthPx) {
                // Allow the content to be smaller than the cap; never force it bigger.
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
