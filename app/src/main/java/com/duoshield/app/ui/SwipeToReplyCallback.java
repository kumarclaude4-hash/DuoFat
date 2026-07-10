package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.R;

public abstract class SwipeToReplyCallback
        extends ItemTouchHelper.SimpleCallback {

    private final Drawable icon;
    private boolean triggered = false;

    protected SwipeToReplyCallback(Context ctx) {
        super(0, ItemTouchHelper.RIGHT);
        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_reply);
    }

    // Cap how far the row is allowed to visually travel, as a fraction of its
    // width. This is a reply *gesture*, not a dismiss — the row must never be
    // draggable far enough to leave the screen.
    private static final float MAX_DRAG_FRACTION = 0.20f;
    private static final float TRIGGER_FRACTION  = 0.25f;

    @Override
    public boolean onMove(@NonNull RecyclerView rv,
                          @NonNull RecyclerView.ViewHolder vh,
                          @NonNull RecyclerView.ViewHolder target) { return false; }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        // Swipe visual is handled in onChildDraw; no action needed here.
    }

    // IMPORTANT: this threshold isn't just cosmetic — ItemTouchHelper uses it
    // internally to decide whether a released swipe counts as a *completed*
    // dismiss gesture (the same mechanism swipe-to-delete relies on). At 0.25f
    // the library treated our reply swipe as a real "swiped away" once the
    // user passed 25% width, played its own fling-out animation that carried
    // the row fully off-screen, and then called onSwiped() expecting us to
    // remove the item via notifyItemRemoved(). Since we deliberately don't
    // remove anything here, the row's View was left stranded off-screen
    // instead of being reset, and only reappeared once something forced a
    // fresh bind of that position (e.g. reopening the chat rebuilds the
    // RecyclerView from scratch) — that was the "message disappears until you
    // reopen the chat" bug. Returning a value the drag can never reach makes
    // ItemTouchHelper always resolve the release as "below threshold", so it
    // auto-recovers the row back to position 0 itself every time. Our own
    // reply trigger below is intentionally separate and keeps firing at 25%.
    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) { return 2f; }

    // Same idea, but for fast flicks: a quick swipe can beat the distance
    // threshold via velocity alone. Push this out of reach too so a flick
    // can't trigger the library's "swiped away" completion path either.
    @Override
    public float getSwipeEscapeVelocity(float defaultValue) { return Float.MAX_VALUE; }

    @Override
    public float getSwipeVelocityThreshold(float defaultValue) { return Float.MAX_VALUE; }

    @Override
    public void onChildDraw(@NonNull Canvas c,
                            @NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder vh,
                            float dX, float dY, int state, boolean active) {
        android.view.View itemView = vh.itemView;

        // Now that ItemTouchHelper's own distance/velocity thresholds are
        // unreachable (see getSwipeThreshold/getSwipeEscapeVelocity above),
        // it no longer caps how far the raw finger delta (dX) can grow while
        // the gesture is active — it'll just keep tracking the finger. Clamp
        // the value we actually draw/translate with so the row rubber-bands
        // to a stop instead of being draggable across the screen. The trigger
        // check further down still uses the *raw* dX, so the finger still has
        // to travel the full TRIGGER_FRACTION even though the row visually
        // stops earlier — same rubber-band feel WhatsApp/Telegram use.
        float drawDX = dX > 0
                ? Math.min(dX, itemView.getWidth() * MAX_DRAG_FRACTION)
                : dX;

        // Only draw the reply icon while the user is actively dragging this row
        // (active == true). During the spring-back/recover animation ItemTouchHelper
        // keeps calling onChildDraw with a non-IDLE state for a few more frames even
        // though the finger has already lifted — checking `state` alone let the icon
        // "stick"/flicker at a stale position (the reported swipe glitch). Checking
        // `active` instead guarantees the icon only renders while the user's finger
        // is actually down on this ViewHolder.
        if (dX > 0 && active && icon != null) {
            // Clip drawing to the item's bounds so the icon never bleeds into
            // adjacent rows, even when RecyclerView skips a full redraw.
            c.save();
            c.clipRect(itemView.getLeft(), itemView.getTop(),
                       itemView.getRight(), itemView.getBottom());

            int iconSize = icon.getIntrinsicWidth();
            int margin   = (itemView.getHeight() - iconSize) / 2;
            int top      = itemView.getTop()  + margin;
            int bottom   = top + iconSize;
            int left     = itemView.getLeft() + margin;
            int right    = left + iconSize;

            float alpha = Math.min(1f, drawDX / (itemView.getWidth() * 0.2f));
            icon.setAlpha((int)(alpha * 255));
            icon.setBounds(left, top, right, bottom);
            icon.draw(c);

            c.restore();

            if (!triggered && dX > itemView.getWidth() * TRIGGER_FRACTION) {
                triggered = true;
                onSwipeTriggered(vh.getAdapterPosition());
            }
        }
        super.onChildDraw(c, rv, vh, drawDX, dY, state, active);
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        triggered = false;
    }

    public abstract void onSwipeTriggered(int adapterPosition);
}
