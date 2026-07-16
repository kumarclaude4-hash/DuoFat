package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Random;

/**
 * Custom View: 3×3 grid of scrambling symbol cells that lock in one by one.
 */
public class DecryptGridView extends View {

    private static final String[] GLYPHS = {
        "!", "@", "#", "$", "%", "Ω", "∑", "≈", "≡", "Ψ", "Δ", "0", "1", "β", "Σ", "∂"
    };

    private static final int[] LOCK_ORDER = {4, 0, 8, 2, 6, 1, 3, 5, 7};

    private final String[]  cellChars = new String[9];
    private final boolean[] locked    = new boolean[9];

    private final Handler scrambleHandler = new Handler(Looper.getMainLooper());
    private final Handler lockHandler     = new Handler(Looper.getMainLooper());
    private final Random  random          = new Random();

    private int lockStep = 0;

    // Pre-allocated per-cell RectFs — set in onSizeChanged, reused in onDraw
    private final RectF[] cellRects = new RectF[9];

    private final Paint bgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DecryptGridView(Context context) {
        super(context);
        init();
    }

    public DecryptGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DecryptGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        for (int i = 0; i < 9; i++) {
            cellChars[i] = "?";
            locked[i]    = false;
            cellRects[i] = new RectF();
        }

        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(13f));
    }

    // ── Animation control ─────────────────────────────────────────────────────

    public void startAnimation() {
        stopAnimation();
        lockStep = 0;
        for (int i = 0; i < 9; i++) {
            cellChars[i] = "?";
            locked[i]    = false;
        }
        postScramble();
        scheduleLocks();
    }

    public void stopAnimation() {
        scrambleHandler.removeCallbacksAndMessages(null);
        lockHandler.removeCallbacksAndMessages(null);
    }

    public void reset() {
        for (int i = 0; i < 9; i++) {
            cellChars[i] = "?";
            locked[i]    = false;
        }
        lockStep = 0;
        invalidate();
    }

    // ── Internal scheduling ───────────────────────────────────────────────────

    private final Runnable scrambleRunnable = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < 9; i++) {
                if (!locked[i]) {
                    cellChars[i] = GLYPHS[random.nextInt(GLYPHS.length)];
                }
            }
            invalidate();
            scrambleHandler.postDelayed(this, 80);
        }
    };

    private void postScramble() {
        scrambleHandler.post(scrambleRunnable);
    }

    private void scheduleLocks() {
        for (int step = 0; step < LOCK_ORDER.length; step++) {
            final int s = step;
            lockHandler.postDelayed(() -> {
                int idx = LOCK_ORDER[s];
                locked[idx]    = true;
                cellChars[idx] = "✓";
                invalidate();

                // After all cells locked, wait 800 ms then reset and restart
                if (s == LOCK_ORDER.length - 1) {
                    lockHandler.postDelayed(() -> {
                        reset();
                        startAnimation();
                    }, 800);
                }
            }, s * 320L);
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cellSize = dp(30f);
        float gap      = dp(6f);
        float padding  = dp(3f);
        for (int i = 0; i < 9; i++) {
            int col = i % 3, row = i / 3;
            float left = padding + col * (cellSize + gap);
            float top  = padding + row * (cellSize + gap);
            cellRects[i].set(left, top, left + cellSize, top + cellSize);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cellSize = dp(30f);
        float gap      = dp(6f);
        float corner   = dp(7f);
        float padding  = dp(3f);

        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;

            float left   = padding + col * (cellSize + gap);
            float top    = padding + row * (cellSize + gap);
            float right  = left + cellSize;
            float bottom = top  + cellSize;

            RectF rect = cellRects[i]; // pre-allocated, no per-frame alloc

            // Background
            bgPaint.setColor(locked[i] ? 0x207C6BFF : 0xFF2D2938);
            canvas.drawRoundRect(rect, corner, corner, bgPaint);

            // Border
            borderPaint.setColor(locked[i] ? 0xFF7C6BFF : 0xFF3A3548);
            canvas.drawRoundRect(rect, corner, corner, borderPaint);

            // Text
            textPaint.setColor(locked[i] ? 0xFF7C6BFF : 0xFFC8C2D8);
            String ch = cellChars[i] != null ? cellChars[i] : "?";
            float cx = left + cellSize / 2f;
            // Center vertically: baseline offset
            float cy = top + cellSize / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(ch, cx, cy, textPaint);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) dp(108f);
        setMeasuredDimension(
                resolveSize(size, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
