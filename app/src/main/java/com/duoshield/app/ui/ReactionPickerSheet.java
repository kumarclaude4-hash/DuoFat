package com.duoshield.app.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.Arrays;
import java.util.List;

/**
 * Full emoji picker for message reactions: a search box above a category-tabbed grid.
 *
 * <p>Deliberately not a subclass or variant of {@link EmojiKeyboardHelper}. That class is
 * hard-wired to splice text into an {@link EditText} at the cursor; a reaction has no
 * cursor and no text field, it reports a single chosen glyph and closes. The two share the
 * catalogue ({@link EmojiData}) and visual styling but not their output behaviour.
 *
 * <p>Typing in the search box hides the tab bar and shows a flat result grid spanning every
 * category, because a reaction search is a hunt for one specific emoji and forcing the user
 * to guess its category first defeats the point. Clearing the box restores the tabs.
 */
public class ReactionPickerSheet {

    /** Receives the chosen emoji. Not called if the sheet is dismissed without a pick. */
    public interface OnEmojiPickedListener {
        void onEmojiPicked(String emoji);
    }

    private static final int BG        = 0xFF0F1620;
    private static final int DIVIDER   = 0xFF1A2535;
    private static final int TEXT_HINT = 0xFF6B7A8F;
    private static final int TEXT_MAIN = 0xFFE6EDF5;
    private static final int COLUMNS   = 8;

    private final Context ctx;
    private final OnEmojiPickedListener listener;
    /** The emoji currently registered for this user on the target message, or null. */
    private final String currentSelection;

    private BottomSheetDialog dialog;
    private LinearLayout      tabWrap;
    private GridLayout        grid;
    private TextView[]        tabs;
    private int               activeCat = 0;

    public ReactionPickerSheet(Context ctx, String currentSelection,
                               OnEmojiPickedListener listener) {
        this.ctx              = ctx;
        this.currentSelection = currentSelection;
        this.listener         = listener;
    }

    public void show() {
        dialog = new BottomSheetDialog(ctx,
                com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ── Search box ──────────────────────────────────────────────────────
        EditText search = new EditText(ctx);
        search.setHint("Search emoji");
        search.setHintTextColor(TEXT_HINT);
        search.setTextColor(TEXT_MAIN);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        search.setLayoutParams(searchLp);
        root.addView(search);
        root.addView(divider());

        // ── Category tab bar (hidden while searching) ───────────────────────
        tabWrap = new LinearLayout(ctx);
        tabWrap.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(true);
        tabScroll.setBackgroundColor(BG);
        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(6), dp(6), dp(6), 0);
        tabScroll.addView(tabBar);

        tabs = new TextView[EmojiData.CAT_ICONS.length];
        for (int i = 0; i < EmojiData.CAT_ICONS.length; i++) {
            final int idx = i;
            TextView tab = new TextView(ctx);
            tab.setText(EmojiData.CAT_ICONS[i]);
            tab.setContentDescription(EmojiData.CAT_NAMES[i]);
            tab.setTextSize(22);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(10), dp(8), dp(10), dp(8));
            tab.setMinWidth(dp(48));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> {
                activeCat = idx;
                highlightTabs();
                renderCategory(idx);
            });
            tabs[i] = tab;
            tabBar.addView(tab);
        }
        tabWrap.addView(tabScroll);
        tabWrap.addView(divider());
        root.addView(tabWrap);

        // ── Grid ────────────────────────────────────────────────────────────
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        scroll.setBackgroundColor(BG);
        grid = new GridLayout(ctx);
        grid.setColumnCount(COLUMNS);
        grid.setBackgroundColor(BG);
        int pad = dp(4);
        grid.setPadding(pad, pad, pad, pad);
        scroll.addView(grid);
        root.addView(scroll);

        highlightTabs();
        renderCategory(activeCat);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                if (q.isEmpty()) {
                    tabWrap.setVisibility(View.VISIBLE);
                    renderCategory(activeCat);
                } else {
                    tabWrap.setVisibility(View.GONE);
                    renderList(EmojiData.search(q));
                }
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private void renderCategory(int cat) {
        renderList(Arrays.asList(EmojiData.emojisIn(cat)));
    }

    private void renderList(List<String> emojis) {
        grid.removeAllViews();
        if (emojis.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No emoji found");
            empty.setTextColor(TEXT_HINT);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(0, COLUMNS, 1f));
            lp.width = 0;
            empty.setLayoutParams(lp);
            grid.addView(empty);
            return;
        }
        for (String em : emojis) grid.addView(cell(em));
    }

    private TextView cell(String emoji) {
        TextView tv = new TextView(ctx);
        tv.setText(emoji);
        tv.setTextSize(26);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(2), dp(4), dp(2), dp(4));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setMinWidth(dp(40));
        tv.setMinHeight(dp(44));

        // Show which emoji is already mine, so the toggle-to-remove tap is discoverable.
        if (emoji.equals(currentSelection)) {
            tv.setBackgroundColor(DIVIDER);
        } else {
            android.util.TypedValue attr = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, attr, true);
            tv.setBackgroundResource(attr.resourceId);
        }

        tv.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiPicked(emoji);
            dismiss();
        });

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width  = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        tv.setLayoutParams(lp);
        return tv;
    }

    private void highlightTabs() {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundColor(i == activeCat
                    ? DIVIDER : android.graphics.Color.TRANSPARENT);
            tabs[i].setAlpha(i == activeCat ? 1f : 0.55f);
        }
    }

    private View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public void dismiss() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
