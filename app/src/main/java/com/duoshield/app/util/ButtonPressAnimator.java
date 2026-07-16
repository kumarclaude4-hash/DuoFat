package com.duoshield.app.util;

import android.animation.ObjectAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Attaches a subtle scale + haptic press effect to a view per the design spec:
 * - ACTION_DOWN: animate scaleX/scaleY to 0.98 over 80ms + light haptic
 * - ACTION_UP / ACTION_CANCEL: animate scaleX/scaleY back to 1.0 over 80ms
 */
public class ButtonPressAnimator {

    private ButtonPressAnimator() {}

    /**
     * Attach the press animation and light haptic to the given view.
     * The view's existing OnClickListener is preserved.
     */
    public static void attach(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(v, 0.98f);
                    HapticHelper.lightPress(v.getContext());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateScale(v, 1.0f);
                    break;
            }
            // Return false so click events are still delivered
            return false;
        });
    }

    private static void animateScale(View v, float target) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, View.SCALE_X, target);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, View.SCALE_Y, target);
        scaleX.setDuration(80);
        scaleY.setDuration(80);
        scaleX.setInterpolator(new DecelerateInterpolator());
        scaleY.setInterpolator(new DecelerateInterpolator());
        scaleX.start();
        scaleY.start();
    }
}
