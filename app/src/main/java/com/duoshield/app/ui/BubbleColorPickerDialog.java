package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.duoshield.app.util.ChatCustomizationHelper;

/**
 * A full-colour-wheel picker dialog for bubble colours.
 *
 * <p>Shows:
 * <ol>
 *   <li>An HSV colour wheel (hue + saturation via {@link ColorWheelView})</li>
 *   <li>A brightness SeekBar</li>
 *   <li>A live preview swatch + hex input field</li>
 * </ol>
 * Calls {@link OnColorPickedListener#onColorPicked(int)} with the chosen ARGB
 * colour when the user taps "Apply".
 */
public final class BubbleColorPickerDialog {

    public interface OnColorPickedListener {
        void onColorPicked(int color);
    }

    private final Context            ctx;
    private final int                initialColor;
    private final OnColorPickedListener listener;

    public BubbleColorPickerDialog(Context ctx, int initialColor,
                                   OnColorPickedListener listener) {
        this.ctx          = ctx;
        this.initialColor = initialColor;
        this.listener     = listener;
    }

    public void show() {
        float dp  = ctx.getResources().getDisplayMetrics().density;
        int   pad = (int) (20 * dp);

        // ── Root container ───────────────────────────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * dp), pad, (int) (4 * dp));

        // ── Colour wheel ─────────────────────────────────────────────────
        ColorWheelView wheel = new ColorWheelView(ctx);
        int wheelPx = (int) (224 * dp);
        LinearLayout.LayoutParams wheelLp =
                new LinearLayout.LayoutParams(wheelPx, wheelPx);
        wheelLp.gravity = Gravity.CENTER_HORIZONTAL;
        wheel.setLayoutParams(wheelLp);
        root.addView(wheel);

        // ── Brightness label ─────────────────────────────────────────────
        TextView brightnessLabel = new TextView(ctx);
        brightnessLabel.setText("Brightness");
        brightnessLabel.setTextColor(0xFFAAAAAA);
        brightnessLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams blp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (14 * dp);
        root.addView(brightnessLabel, blp);

        // ── Brightness SeekBar ───────────────────────────────────────────
        SeekBar brightnessBar = new SeekBar(ctx);
        brightnessBar.setMax(100);
        LinearLayout.LayoutParams sbLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = (int) (2 * dp);
        root.addView(brightnessBar, sbLp);

        // ── Preview swatch + hex row ─────────────────────────────────────
        LinearLayout previewRow = new LinearLayout(ctx);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = (int) (14 * dp);
        root.addView(previewRow, prLp);

        // Circular colour preview swatch
        View previewSwatch = new View(ctx);
        int swatchPx = (int) (44 * dp);
        LinearLayout.LayoutParams swLp =
                new LinearLayout.LayoutParams(swatchPx, swatchPx);
        swLp.setMarginEnd((int) (14 * dp));
        previewSwatch.setLayoutParams(swLp);
        GradientDrawable swatchBg = ChatCustomizationHelper.buildSwatchCircle(initialColor, dp);
        previewSwatch.setBackground(swatchBg);
        previewRow.addView(previewSwatch);

        // Hex input
        EditText hexInput = new EditText(ctx);
        hexInput.setSingleLine(true);
        hexInput.setTextColor(0xFFEEEEEE);
        hexInput.setHint("#RRGGBB");
        hexInput.setHintTextColor(0xFF666666);
        hexInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        hexInput.setBackground(null);
        hexInput.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(9) });
        hexInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        previewRow.addView(hexInput);

        // ── State ────────────────────────────────────────────────────────
        final int[] currentColor = { initialColor | 0xFF000000 };

        // Initialise wheel and slider from the initial colour.
        wheel.setColor(currentColor[0]);
        float[] initHsv = new float[3];
        Color.colorToHSV(currentColor[0], initHsv);
        brightnessBar.setProgress(Math.round(initHsv[2] * 100));
        hexInput.setText(toHex(currentColor[0]));

        // ── Hex-watcher placeholder (filled below) ───────────────────────
        final TextWatcher[] hexWatcherRef = { null };

        // ── Wheel → swatch + hex ─────────────────────────────────────────
        wheel.setOnColorChangedListener(color -> {
            int c = color | 0xFF000000;
            currentColor[0] = c;
            swatchBg.setColor(c);

            String hex = toHex(c);
            String cur = hexInput.getText().toString().trim();
            if (!cur.equalsIgnoreCase(hex)) {
                if (hexWatcherRef[0] != null)
                    hexInput.removeTextChangedListener(hexWatcherRef[0]);
                hexInput.setText(hex);
                hexInput.setSelection(hex.length());
                if (hexWatcherRef[0] != null)
                    hexInput.addTextChangedListener(hexWatcherRef[0]);
            }
        });

        // ── Brightness SeekBar → wheel ───────────────────────────────────
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) wheel.setBrightness(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar)  {}
        });

        // ── Hex input → wheel ────────────────────────────────────────────
        hexWatcherRef[0] = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable e) {
                String raw = e.toString().trim().replace("#", "");
                if (raw.length() == 6) {
                    try {
                        int parsed = Color.parseColor("#" + raw) | 0xFF000000;
                        // Temporarily detach to avoid recursive updates.
                        hexInput.removeTextChangedListener(hexWatcherRef[0]);
                        wheel.setColor(parsed);
                        hexInput.addTextChangedListener(hexWatcherRef[0]);
                        swatchBg.setColor(parsed);
                        float[] hsv2 = new float[3];
                        Color.colorToHSV(parsed, hsv2);
                        brightnessBar.setProgress(Math.round(hsv2[2] * 100));
                        currentColor[0] = parsed;
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        };
        hexInput.addTextChangedListener(hexWatcherRef[0]);

        // ── Dialog ───────────────────────────────────────────────────────
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("Custom Colour")
                .setView(root)
                .setPositiveButton("Apply",  (d, w) -> listener.onColorPicked(currentColor[0]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String toHex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }
}
