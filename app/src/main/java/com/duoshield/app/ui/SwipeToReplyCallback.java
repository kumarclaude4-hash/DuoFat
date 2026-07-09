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

    @Override
    public boolean onMove(@NonNull RecyclerView rv,
                          @NonNull RecyclerView.ViewHolder vh,
                          @NonNull RecyclerView.ViewHolder target) { return false; }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        // Swipe visual is handled in onChildDraw; no action needed here.
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) { return 0.25f; }

    @Override
    public void onChildDraw(@NonNull Canvas c,
                            @NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder vh,
                            float dX, float dY, int state, boolean active) {
        android.view.View itemView = vh.itemView;

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

            float alpha = Math.min(1f, dX / (itemView.getWidth() * 0.2f));
            icon.setAlpha((int)(alpha * 255));
            icon.setBounds(left, top, right, bottom);
            icon.draw(c);

            c.restore();

            if (!triggered && dX > itemView.getWidth() * 0.25f) {
                triggered = true;
                onSwipeTriggered(vh.getAdapterPosition());
            }
        }
        super.onChildDraw(c, rv, vh, dX, dY, state, active);
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        triggered = false;
    }

    public abstract void onSwipeTriggered(int adapterPosition);
}
