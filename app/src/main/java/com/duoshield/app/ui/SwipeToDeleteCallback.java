package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.R;

public abstract class SwipeToDeleteCallback
        extends ItemTouchHelper.SimpleCallback {

    private final ColorDrawable background = new ColorDrawable(0xFFB00020);
    private final Drawable      icon;

    protected SwipeToDeleteCallback(Context ctx) {
        super(0, ItemTouchHelper.LEFT);
        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_delete);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView rv,
                          @NonNull RecyclerView.ViewHolder vh,
                          @NonNull RecyclerView.ViewHolder target) { return false; }

    @Override
    public void onChildDraw(@NonNull Canvas c,
                            @NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder vh,
                            float dX, float dY, int state, boolean active) {
        View itemView = vh.itemView;
        if (dX < 0) {
            background.setBounds((int)(itemView.getRight() + dX),
                itemView.getTop(), itemView.getRight(), itemView.getBottom());
            background.draw(c);
            if (icon != null) {
                int margin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                int top    = itemView.getTop()  + margin;
                int bottom = itemView.getBottom() - margin;
                int left   = itemView.getRight() - margin - icon.getIntrinsicWidth();
                int right  = itemView.getRight() - margin;
                icon.setBounds(left, top, right, bottom);
                icon.draw(c);
            }
        }
        super.onChildDraw(c, rv, vh, dX, dY, state, active);
    }
}
