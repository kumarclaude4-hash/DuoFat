package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas-based amplitude visualiser used for both live recording
 * and static playback waveforms.
 *
 * Recording mode: call {@link #addAmplitude(int)} on each tick (0–100, pre-normalised by VoiceRecorderHelper).
 * Playback mode:  call {@link #setAmplitudes(List)} then {@link #setProgress(float)}
 *                 (0.0–1.0) to shade the played portion in the accent colour.
 */
public class WaveformView extends View {

    private static final int   MAX_BARS      = 60;
    private static final int   BAR_GAP_DP    = 2;
    private static final int   MIN_BAR_H_DP  = 3;
    private static final int   COLOR_PLAYED  = 0xFF9A81FF;
    private static final int   COLOR_UNPLAYED = 0xFF3A3548;
    private static final int   COLOR_THUMB   = 0xFFFFFFFF;
    private static final float THUMB_RADIUS_DP = 5.5f;

    private final Paint  paintPlayed   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint  paintUnplayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint  paintThumb    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Float> bars     = new ArrayList<>();

    private float progress = 0f;
    private float density;

    public WaveformView(Context ctx) { this(ctx, null); }
    public WaveformView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        paintPlayed.setColor(COLOR_PLAYED);
        paintUnplayed.setColor(COLOR_UNPLAYED);
        paintThumb.setColor(COLOR_THUMB);
    }

    /**
     * Recording: push one normalised amplitude sample (0–100), as delivered by
     * {@code VoiceRecorderHelper.RecorderListener#onAmplitude(int)}.
     * Do NOT pass a raw MediaRecorder value (0–32767) here; VoiceRecorderHelper
     * already normalises before firing the callback.
     */
    public void addAmplitude(int normalised) {
        // VoiceRecorderHelper delivers 0-100; divide by 100 to get 0-1.
        float norm = Math.min(1f, normalised / 100f);
        norm = Math.max(0.05f, norm); // always show at least a tiny bar
        if (bars.size() >= MAX_BARS) bars.remove(0);
        bars.add(norm);
        invalidate();
    }

    /** Playback: provide the full raw amplitude list. Normalises to the actual max. */
    public void setAmplitudes(List<Integer> rawList) {
        bars.clear();
        if (rawList == null || rawList.isEmpty()) {
            progress = 0f;
            invalidate();
            return;
        }

        int maxAmp = 1;
        for (int v : rawList) {
            if (v > maxAmp) maxAmp = v;
        }

        // Resample down to MAX_BARS using linear index mapping so long recordings
        // don't overcrowd the view with tiny illegible bars.
        int count = Math.min(MAX_BARS, rawList.size());
        for (int i = 0; i < count; i++) {
            int srcIdx = (int) ((i * (rawList.size() - 1f)) / Math.max(1f, count - 1f));
            int v = rawList.get(srcIdx);
            float norm = (float) v / maxAmp;
            bars.add(Math.max(0.05f, norm));
        }
        progress = 0f;
        invalidate();
    }

    /**
     * Returns the normalised amplitude (0f–1f) at the given playback fraction.
     * Uses linear interpolation between adjacent bars so the bubble motion stays
     * smooth even if the stored waveform is sparse.
     */
    public float getAmplitudeAt(float fraction) {
        if (bars.isEmpty()) return 0f;
        if (bars.size() == 1) return bars.get(0);

        float clamped = Math.max(0f, Math.min(1f, fraction));
        float exactIdx = clamped * (bars.size() - 1f);
        int leftIdx = (int) Math.floor(exactIdx);
        int rightIdx = Math.min(bars.size() - 1, leftIdx + 1);
        float t = exactIdx - leftIdx;
        float left = bars.get(leftIdx);
        float right = bars.get(rightIdx);
        return left + (right - left) * t;
    }

    /** Set playback progress fraction (0–1). Triggers redraw. */
    public void setProgress(float fraction) {
        progress = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    /** Reset to blank state. */
    public void clear() {
        bars.clear();
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float gap      = BAR_GAP_DP * density;
        float minBarH  = MIN_BAR_H_DP * density;
        float barCount = bars.isEmpty() ? MAX_BARS : bars.size();
        float barW     = (w - gap * (barCount - 1)) / barCount;
        if (barW < 1f) barW = 1f;

        // Cap bar width so bars look like WhatsApp thin bars, not fat rectangles
        if (barW > 3f * density) barW = 3f * density;

        int playedUpTo = (int) (progress * bars.size());

        if (bars.isEmpty()) {
            // Draw a flat centre line as placeholder
            canvas.drawRect(0, h / 2f - density, w, h / 2f + density, paintUnplayed);
            return;
        }

        // Recompute spacing with capped bar width
        float totalBarsWidth = barW * bars.size();
        float totalGapWidth  = gap * (bars.size() - 1);
        float offsetX = (w - totalBarsWidth - totalGapWidth) / 2f;
        if (offsetX < 0) offsetX = 0;

        for (int i = 0; i < bars.size(); i++) {
            float norm    = bars.get(i);
            float barH    = Math.max(minBarH, norm * h);
            float top     = (h - barH) / 2f;
            float left    = offsetX + i * (barW + gap);
            float right   = left + barW;
            float bottom  = top + barH;

            Paint paint = (i < playedUpTo) ? paintPlayed : paintUnplayed;
            canvas.drawRoundRect(left, top, right, bottom, barW / 2f, barW / 2f, paint);
        }

        // WhatsApp-style scrubber dot riding the played/unplayed boundary, so the
        // waveform reads as an actual playback position rather than a static graphic.
        if (progress > 0f) {
            float trackWidth = totalBarsWidth + totalGapWidth;
            float thumbX      = offsetX + progress * trackWidth;
            float thumbR      = THUMB_RADIUS_DP * density;
            canvas.drawCircle(Math.min(thumbX, w - thumbR), h / 2f, thumbR, paintThumb);
        }
    }
}
