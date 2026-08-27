package com.duoshield.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.duoshield.app.util.MotionBudget;

/**
 * The "Trust Seal": a deterministic, radially-symmetric emblem generated from the shared
 * verification seed of a conversation (see {@link com.duoshield.app.util.SafetyWords#combinedSeed}).
 *
 * <p><b>What it is for.</b> Two contacts who have the same identity keys produce the same seed, so
 * they draw the <em>pixel-identical</em> emblem. Comparing a single picture — "do we both see this
 * shape?" — is dramatically faster and less error-prone than reading 64 hex digits aloud, and the
 * radial symmetry makes any difference jump out. It sits on top of, and never replaces, the
 * authoritative fingerprint/QR check.
 *
 * <p><b>How it is generated.</b> Every visual parameter is drawn from seed bytes: the symmetry
 * order (5–8 arms), the number of concentric rings, the rotation, and per-node presence and
 * colour. Nothing is random — the same seed always yields the same emblem — and the geometry is
 * computed once in {@link #rebuild()}, not per frame.
 *
 * <p><b>States.</b> Before a partner key is known the seed is null and the view draws a quiet
 * placeholder ring. Once sealed (the contact is verified and current) an outer accent ring and a
 * centre check are added so the trusted state reads at a glance.
 *
 * <p><b>Motion.</b> A one-shot "bloom" entrance is the only animation, and it is gated through
 * {@link MotionBudget} so it honours the OS reduce-motion setting and low-tier frame caps exactly
 * like the app's other decorative views.
 */
public class TrustGlyphView extends View {

    // Lavender family, consistent with the app's brand palette. Node colours are chosen from this
    // by seed so the emblem stays on-brand while still being unique per conversation.
    private static final int[] PALETTE = {
        0xFF7C6BFF, 0xFF9A81FF, 0xFFB39DFF, 0xFF6C63FF, 0xFFCBB4FF
    };

    private final Paint ringPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sealPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path   checkPath = new Path();

    private float dp;
    private int   verifiedColor = 0xFF3DDC97;

    private byte[] seed;
    private boolean verified;

    // Derived geometry (rebuilt only when seed/size changes).
    private int     symmetry  = 6;
    private int     ringCount = 4;
    private float   rotation  = 0f;
    private Node[]  nodes     = new Node[0];

    // One-shot entrance.
    private ValueAnimator bloomAnimator;
    private float bloom = 1f;
    private long  frameIntervalMs = 0L;
    private long  lastFrameUptime = 0L;

    private static final class Node {
        float radiusFrac;   // 0..1 of usable radius
        int   arm;          // which symmetry arm
        int   color;
        float   dotFrac;      // dot radius as fraction of usable radius
        boolean spoke;      // draw a connecting spoke to the centre
        float   armPhaseTag;  // per-ring angular offset, keeps rings from lining up radially
    }

    public TrustGlyphView(Context context) { super(context); init(); }
    public TrustGlyphView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public TrustGlyphView(Context context, AttributeSet attrs, int def) {
        super(context, attrs, def); init();
    }

    private void init() {
        dp = getContext().getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * dp);
        ringPaint.setColor(0x33FFFFFF);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f * dp);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        nodePaint.setStyle(Paint.Style.FILL);
        corePaint.setStyle(Paint.Style.FILL);

        sealPaint.setStyle(Paint.Style.STROKE);
        sealPaint.setStrokeWidth(2.5f * dp);

        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(2.5f * dp);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(Color.WHITE);

        setContentDescription("Trust seal emblem, unique to this conversation");
    }

    /** Accent colour for the sealed (verified) outer ring and centre badge. */
    public void setVerifiedColor(int color) {
        this.verifiedColor = color;
        invalidate();
    }

    /**
     * Sets the shared verification seed and rebuilds the emblem. Passing {@code null} clears it
     * back to the placeholder ring.
     */
    public void setSeed(byte[] seed) {
        this.seed = seed;
        rebuild();
        startBloom();
        invalidate();
    }

    /** Marks the emblem as sealed/trusted (adds the outer ring and centre check). */
    public void setVerified(boolean verified) {
        if (this.verified == verified) return;
        this.verified = verified;
        invalidate();
    }

    private int b(int index) {
        // Safe unsigned byte access with wraparound so we never index out of range.
        if (seed == null || seed.length == 0) return 0;
        return seed[index % seed.length] & 0xFF;
    }

    private void rebuild() {
        if (seed == null || seed.length < 8) {
            nodes = new Node[0];
            return;
        }
        symmetry  = 5 + (b(0) & 0x03);        // 5..8 arms
        ringCount = 3 + (b(1) % 3);           // 3..5 rings
        rotation  = (b(2) / 255f) * (float) (2 * Math.PI);

        // One node per (ring, arm); presence and styling derived from the seed. Because every arm
        // in a ring shares the same parameters, the result is inherently rotationally symmetric.
        Node[] built = new Node[ringCount * symmetry];
        int n = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            float radiusFrac = (ring + 1) / (float) (ringCount + 0.5f);
            int   colorIdx   = b(3 + ring) % PALETTE.length;
            float dotFrac    = 0.05f + (b(8 + ring) % 5) * 0.012f;
            boolean spoke    = (b(13 + ring) & 0x01) == 1;
            float armPhase   = (b(18 + ring) / 255f) * (float) (Math.PI / symmetry);
            for (int arm = 0; arm < symmetry; arm++) {
                Node node = new Node();
                node.radiusFrac = radiusFrac;
                node.arm        = arm;
                node.color      = PALETTE[colorIdx];
                node.dotFrac    = dotFrac;
                node.spoke      = spoke;
                // store per-arm phase folded into rotation via arm index; keep armPhase per ring
                node.armPhaseTag = armPhase;
                built[n++] = node;
            }
        }
        nodes = built;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float usable = Math.min(w, h) / 2f * 0.82f;
        float grow = 0.55f + 0.45f * bloom;   // subtle scale-in during the bloom

        // Placeholder ring before a seed is available.
        if (seed == null || nodes.length == 0) {
            ringPaint.setAlpha((int) (60 * bloom + 40));
            canvas.drawCircle(cx, cy, usable * 0.72f, ringPaint);
            corePaint.setColor(0x669A81FF);
            canvas.drawCircle(cx, cy, 4f * dp, corePaint);
            return;
        }

        // Faint guide rings.
        ringPaint.setAlpha(38);
        for (int ring = 0; ring < ringCount; ring++) {
            float rf = (ring + 1) / (float) (ringCount + 0.5f);
            canvas.drawCircle(cx, cy, usable * rf * grow, ringPaint);
        }

        float armStep = (float) (2 * Math.PI / symmetry);

        // Spokes first (under the dots).
        for (Node node : nodes) {
            if (!node.spoke) continue;
            float angle = rotation + node.arm * armStep + node.armPhaseTag;
            float r = usable * node.radiusFrac * grow;
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            linePaint.setColor(node.color);
            linePaint.setAlpha((int) (120 * bloom));
            canvas.drawLine(cx, cy, x, y, linePaint);
        }

        // Nodes.
        for (Node node : nodes) {
            float angle = rotation + node.arm * armStep + node.armPhaseTag;
            float r = usable * node.radiusFrac * grow;
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            nodePaint.setColor(node.color);
            nodePaint.setAlpha((int) (255 * bloom));
            canvas.drawCircle(x, y, usable * node.dotFrac, nodePaint);
        }

        // Core.
        corePaint.setColor(verified ? verifiedColor : 0xFFB39DFF);
        corePaint.setAlpha((int) (255 * bloom));
        canvas.drawCircle(cx, cy, 6.5f * dp, corePaint);

        // Sealed state: outer accent ring + centre check.
        if (verified) {
            sealPaint.setColor(verifiedColor);
            sealPaint.setAlpha((int) (255 * bloom));
            canvas.drawCircle(cx, cy, usable * 1.02f * grow, sealPaint);

            float s = 4.5f * dp;
            checkPath.reset();
            checkPath.moveTo(cx - s, cy);
            checkPath.lineTo(cx - s * 0.2f, cy + s * 0.8f);
            checkPath.lineTo(cx + s, cy - s * 0.7f);
            canvas.drawPath(checkPath, checkPaint);
        }
    }

    private void startBloom() {
        if (MotionBudget.staticOnly(getContext())) { bloom = 1f; return; }
        if (bloomAnimator != null && bloomAnimator.isRunning()) bloomAnimator.cancel();
        frameIntervalMs = MotionBudget.frameIntervalMs(getContext());
        lastFrameUptime = 0L;
        bloom = 0f;
        bloomAnimator = ValueAnimator.ofFloat(0f, 1f);
        bloomAnimator.setDuration(760);
        bloomAnimator.setInterpolator(new DecelerateInterpolator());
        bloomAnimator.addUpdateListener(a -> {
            bloom = (float) a.getAnimatedValue();
            if (MotionBudget.shouldDrawFrame(frameIntervalMs, lastFrameUptime)
                    || bloom >= 1f) {
                lastFrameUptime = SystemClock.uptimeMillis();
                invalidate();
            }
        });
        bloomAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bloomAnimator != null) { bloomAnimator.cancel(); bloomAnimator = null; }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int size = (int) (180 * dp);
        setMeasuredDimension(resolveSize(size, wSpec), resolveSize(size, hSpec));
    }
}
