package com.duoshield.app.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Lightweight emoji keyboard panel shown as a BottomSheetDialog.
 * No external dependencies — uses only Android SDK + Material.
 *
 * Usage:
 *   EmojiKeyboardHelper helper = new EmojiKeyboardHelper(context, editText);
 *   // In emojiButton.setOnClickListener: helper.toggle();
 */
public class EmojiKeyboardHelper {

    // ── Emoji data ─────────────────────────────────────────────────────────
    private static final String[] CAT_ICONS = { "😀", "👋", "🐶", "🍕", "⚽", "✈️", "💡", "❤️" };
    private static final String[] CAT_NAMES = {
        "Smileys", "People", "Animals", "Food", "Activities", "Travel", "Objects", "Symbols"
    };

    private static final String[][] EMOJI_DATA = {
        // Smileys & Emotion
        {
            "😀","😁","😂","🤣","😃","😄","😅","😆",
            "😇","😈","😉","😊","😋","😌","😍","🥰",
            "😎","😏","😐","😑","😒","😓","😔","😕",
            "😖","😗","😘","😙","😚","😛","😜","😝",
            "😞","😟","😠","😡","😢","😣","😤","😥",
            "😦","😧","😨","😩","😪","😫","😬","😭",
            "😮","😯","😰","😱","😲","😳","🥺","😴",
            "😵","🤐","🤑","🤒","🤓","🤔","🤕","🤗",
            "🤧","🤨","🤩","🤪","🤫","🤬","🤭","🥵",
            "🥶","🥴","🥳","🥸","🤯","😶‍🌫️","🫠","🥹",
            "😶","💀","☠️","💩","🤡","👹","👺","👻",
            "👾","🤖","😺","😸","😹","😻","😼","😽"
        },
        // People & Gestures
        {
            "👋","🤚","🖐️","✋","🖖","👌","🤌","🤏",
            "✌️","🤞","🤟","🤘","🤙","👈","👉","👆",
            "🖕","👇","☝️","👍","👎","✊","👊","🤛",
            "🤜","🤝","👏","🙌","👐","🤲","🙏","✍️",
            "💅","🤳","💪","🦵","🦶","👂","🦻","👃",
            "🧠","🦷","🦴","👀","👁️","👅","👄","🫦",
            "💋","🧑","👦","👧","👨","👩","👴","👵",
            "🧒","👶","🧑‍🤝‍🧑","💑","👨‍👩‍👧","👨‍👩‍👦","🧑‍💼","🧑‍🎤"
        },
        // Animals & Nature
        {
            "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼",
            "🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈",
            "🙉","🙊","🐔","🐧","🐦","🐤","🦆","🦅",
            "🦉","🦇","🐺","🐗","🐴","🦄","🐝","🐛",
            "🦋","🐌","🐞","🐜","🦗","🕷️","🦂","🐢",
            "🦎","🐍","🐲","🦕","🦖","🦈","🐬","🐳",
            "🐋","🦭","🦞","🦀","🦑","🐙","🦐","🐡",
            "🌸","🌺","🌻","🌹","🌷","🌿","🌱","🍀"
        },
        // Food & Drink
        {
            "🍕","🍔","🌮","🌯","🥙","🧆","🥚","🍳",
            "🥞","🧇","🥓","🍗","🍖","🌭","🍟","🍿",
            "🧂","🧈","🥗","🍱","🍣","🍜","🍝","🍛",
            "🍲","🍥","🥮","🍡","🧁","🎂","🍰","🍮",
            "🍭","🍬","🍫","🍩","🍪","🌰","🥜","🍯",
            "🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓",
            "🫐","🍈","🍑","🥭","🍍","🥥","🥝","🍅",
            "🥤","☕","🍵","🧃","🍺","🍻","🥂","🍷"
        },
        // Activities & Sport
        {
            "⚽","🏀","🏈","⚾","🥎","🏐","🏉","🎾",
            "🥏","🎱","🏓","🏸","🏒","🏑","🥍","🏏",
            "⛳","🪃","🥊","🥋","🎽","🛹","🛼","🛷",
            "🏂","🪂","🤸","⛷️","🏋️","🤼","🤺","🏇",
            "🤾","🏌️","🏄","🤽","🚣","🧘","🏊","🤿",
            "🎯","🎳","🎮","🎰","🧩","🎲","♟️","🎭",
            "🎨","🖼️","🎼","🎤","🎧","🎷","🎺","🎸",
            "🪕","🎻","🥁","🪘","🎬","🎥","📷","📸"
        },
        // Travel & Places
        {
            "✈️","🚀","🛸","🚁","🛩️","🚂","🚃","🚄",
            "🚅","🚆","🚇","🚈","🚉","🚊","🚝","🚞",
            "🚋","🚌","🚍","🚎","🏎️","🚐","🚑","🚒",
            "🚓","🚔","🚕","🚖","🚗","🚘","🛻","🚙",
            "🛵","🏍️","🚲","🛴","🛺","🚏","🛣️","🗺️",
            "🌍","🌎","🌏","🌐","🗾","🧭","🏔️","⛰️",
            "🌋","🗻","🏕️","🏖️","🏜️","🏝️","🏞️","🏟️",
            "🏛️","🗼","🗽","🗿","🏰","🏯","🌁","🌃"
        },
        // Objects
        {
            "💡","🔦","🕯️","🪔","💰","💵","💴","💶",
            "💷","💸","💳","💎","⚖️","🧲","🔧","🔨",
            "⚒️","🛠️","⛏️","🔩","🪛","🪚","🔗","⛓️",
            "🪝","🧰","🧲","🔑","🗝️","🔐","🔒","🔓",
            "📱","💻","🖥️","🖨️","⌨️","🖱️","🖲️","💾",
            "💿","📀","📼","📷","📸","📹","🎥","📞",
            "📟","📠","📺","📻","🧭","⏰","⏱️","⏲️",
            "📦","📫","📪","📬","📭","📮","🗳️","✏️"
        },
        // Symbols & Hearts
        {
            "❤️","🧡","💛","💚","💙","💜","🖤","🤍",
            "🤎","💔","❣️","💕","💞","💓","💗","💖",
            "💘","💝","💟","☮️","✝️","☪️","🕉️","☯️",
            "✡️","🔯","🕎","☦️","⛎","♈","♉","♊",
            "♋","♌","♍","♎","♏","♐","♑","♒",
            "♓","🆔","🆕","🆖","🆗","🆘","🆙","🆚",
            "🈶","🉐","🈹","🈚","🈲","🉑","🈸","🈺",
            "✅","❎","🔴","🟠","🟡","🟢","🔵","🟣"
        }
    };

    // ── State ───────────────────────────────────────────────────────────────
    private final Context ctx;
    private final EditText target;
    private BottomSheetDialog dialog;
    private int activeCat = 0;

    public EmojiKeyboardHelper(Context ctx, EditText target) {
        this.ctx    = ctx;
        this.target = target;
    }

    /** Show if hidden, dismiss if visible. */
    public void toggle() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            dialog = null;
        } else {
            show();
        }
    }

    /** Always show the panel. */
    public void show() {
        dialog = new BottomSheetDialog(ctx, com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1620);

        // ── Category tab bar ────────────────────────────────────────────
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(true);
        tabScroll.setBackgroundColor(0xFF0F1620);
        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(6), dp(6), dp(6), 0);
        tabScroll.addView(tabBar);

        TextView[] tabs = new TextView[CAT_ICONS.length];
        for (int i = 0; i < CAT_ICONS.length; i++) {
            final int idx = i;
            TextView tab = new TextView(ctx);
            tab.setText(CAT_ICONS[i]);
            tab.setTextSize(22);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(10), dp(8), dp(10), dp(8));
            tab.setMinWidth(dp(48));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> selectTab(tabs, idx, gridHolder(root)));
            tabs[i] = tab;
            tabBar.addView(tab);
        }
        root.addView(tabScroll);

        // ── Divider ─────────────────────────────────────────────────────
        View divider = new View(ctx);
        divider.setBackgroundColor(0xFF1A2535);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(divider);

        // ── Emoji grid (wrapped in a ScrollView) ────────────────────────
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        scroll.setBackgroundColor(0xFF0F1620);
        GridLayout grid = buildGrid(activeCat);
        // Tag the root so selectTab can replace it
        root.setTag(grid);
        scroll.addView(grid);
        root.addView(scroll);

        // Highlight active tab
        highlightTab(tabs, activeCat);

        dialog.setContentView(root);
        dialog.show();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Returns the GridLayout embedded in the ScrollView at index 2 of root. */
    private GridLayout gridHolder(LinearLayout root) {
        return (GridLayout) root.getTag();
    }

    private void selectTab(TextView[] tabs, int idx, GridLayout oldGrid) {
        activeCat = idx;
        highlightTab(tabs, idx);
        // Replace grid content
        ViewGroup scroll = (ViewGroup) oldGrid.getParent();
        if (scroll != null) {
            scroll.removeAllViews();
            GridLayout newGrid = buildGrid(idx);
            // Update root tag
            ((LinearLayout) scroll.getParent()).setTag(newGrid);
            scroll.addView(newGrid);
        }
    }

    private void highlightTab(TextView[] tabs, int active) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundColor(i == active ? 0xFF1A2535 : android.graphics.Color.TRANSPARENT);
            tabs[i].setAlpha(i == active ? 1f : 0.55f);
        }
    }

    private GridLayout buildGrid(int catIdx) {
        String[] emojis = EMOJI_DATA[catIdx];
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(8);
        grid.setBackgroundColor(0xFF0F1620);
        int pad = dp(4);
        grid.setPadding(pad, pad, pad, pad);

        for (String em : emojis) {
            TextView tv = new TextView(ctx);
            tv.setText(em);
            tv.setTextSize(26);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(2), dp(4), dp(2), dp(4));
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setMinWidth(dp(40));
            tv.setMinHeight(dp(44));

            android.util.TypedValue tv2 = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv2, true);
            tv.setBackgroundResource(tv2.resourceId);

            tv.setOnClickListener(v -> insertEmoji(em));

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width  = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            tv.setLayoutParams(lp);
            grid.addView(tv);
        }
        return grid;
    }

    private void insertEmoji(String emoji) {
        int start = Math.max(target.getSelectionStart(), 0);
        int end   = Math.max(target.getSelectionEnd(), 0);
        target.getText().replace(Math.min(start, end), Math.max(start, end),
                emoji, 0, emoji.length());
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
