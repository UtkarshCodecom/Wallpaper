package com.infinity.wallpaper.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Improved color picker: large scrollable swatches (selected = yellow ring),
 * gradient rainbow bar, RGB sliders, hex input.
 */
public class ColorPickerDialog {

    public interface OnColorPicked { void onPicked(String hexColor); }

    // Extended curated palette - grouped by hue
    private static final int[] PALETTE = {
        // Whites / Greys / Blacks
        0xFFFFFFFF, 0xFFF5F5F5, 0xFFEEEEEE, 0xFFCCCCCC,
        0xFFAAAAAA, 0xFF888888, 0xFF555555, 0xFF333333,
        0xFF1E1E1E, 0xFF111111, 0xFF000000,
        // Yellows / Golds
        0xFFFFD600, 0xFFFFEB3B, 0xFFFFF176, 0xFFFFC107,
        0xFFFF8F00, 0xFFFF6D00, 0xFFFF8C00, 0xFFE65100,
        // Pinks / Reds
        0xFFFF5FA2, 0xFFFF6EC7, 0xFFFF4081, 0xFFE91E63,
        0xFFFF4444, 0xFFFF1744, 0xFFD50000, 0xFFB71C1C,
        0xFFFF5252, 0xFFFF6B6B,
        // Purples
        0xFF9C27B0, 0xFF7B1FA2, 0xFF6A1B9A, 0xFF651FFF,
        0xFF512DA8, 0xFF311B92, 0xFF9575CD, 0xFFCE93D8,
        // Blues
        0xFF2196F3, 0xFF1976D2, 0xFF0D47A1, 0xFF1565C0,
        0xFF00BCD4, 0xFF00B0FF, 0xFF00E5FF, 0xFF0288D1,
        0xFF01579B, 0xFF90CAF9, 0xFFCAF0F8,
        // Greens
        0xFF4CAF50, 0xFF388E3C, 0xFF1B5E20, 0xFF69F0AE,
        0xFF00E676, 0xFF64DD17, 0xFF76FF03, 0xFFA5D6A7,
        // Teals / Cyans
        0xFF009688, 0xFF00796B, 0xFF004D40, 0xFF00BFA5,
        0xFF80CBC4, 0xFF26A69A,
        // Dark themes
        0xFF1A1A2E, 0xFF16213E, 0xFF0F3460, 0xFF533483,
        0xFF3D1A2E, 0xFF1A0A00, 0xFF0A1A0A, 0xFF0A0A1A,
        // Warm special
        0xFFE94560, 0xFF00B4D8, 0xFF533483, 0xFFFF6B35,
    };

    public static void show(Context ctx, String currentColor, OnColorPicked callback) {
        int[] rgb = {255, 255, 255};
        String[] selectedHex = {currentColor != null ? currentColor : "#FFFFFF"};
        try {
            int c = Color.parseColor(selectedHex[0]);
            rgb[0] = Color.red(c); rgb[1] = Color.green(c); rgb[2] = Color.blue(c);
        } catch (Exception ignored) {}

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().getDecorView().setPadding(0, 0, 0, 0);
        }

        // Root scroll container
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(0xFF111111);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 24));

        sv.addView(root, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Title row ──
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleRowLp.bottomMargin = dp(ctx, 14);
        titleRow.setLayoutParams(titleRowLp);

        TextView titleTv = new TextView(ctx);
        titleTv.setText("Pick a Color");
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(17);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleTv);

        // Preview circle
        View previewCircle = new View(ctx);
        previewCircle.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)));
        setCircleBg(previewCircle, Color.parseColor(selectedHex[0]));
        titleRow.addView(previewCircle);
        root.addView(titleRow);

        // ── Section: Swatches (horizontal scrollable, large) ──
        root.addView(makeSectionLabel(ctx, "COLORS"));

        HorizontalScrollView paletteScroll = new HorizontalScrollView(ctx);
        paletteScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams palScrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        palScrollLp.bottomMargin = dp(ctx, 16);
        paletteScroll.setLayoutParams(palScrollLp);

        LinearLayout paletteRow = new LinearLayout(ctx);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setPadding(dp(ctx, 2), dp(ctx, 6), dp(ctx, 2), dp(ctx, 6));

        // Track swatch views for ring highlight
        View[] swatchViews = new View[PALETTE.length];
        int swSize = dp(ctx, 42);
        int swMargin = dp(ctx, 5);

        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];

            FrameLayout swFrame = new FrameLayout(ctx);
            LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(swSize + dp(ctx, 6), swSize + dp(ctx, 6));
            frameLp.setMargins(swMargin, swMargin, swMargin, swMargin);
            swFrame.setLayoutParams(frameLp);

            View swatch = new View(ctx);
            swatch.setLayoutParams(new FrameLayout.LayoutParams(swSize, swSize, Gravity.CENTER));
            setCircleBg(swatch, color);
            swFrame.addView(swatch);

            // Ring overlay (red accent when selected)
            View ring = new View(ctx);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(swSize + dp(ctx, 4), swSize + dp(ctx, 4), Gravity.CENTER);
            ring.setLayoutParams(ringLp);
            ring.setBackground(makeRingDrawable(ctx, 0xFFD84040));
            ring.setVisibility(isColorMatch(color, selectedHex[0]) ? View.VISIBLE : View.INVISIBLE);
            swFrame.addView(ring);

            swatchViews[i] = ring;

            swFrame.setOnClickListener(v -> {
                rgb[0] = Color.red(color); rgb[1] = Color.green(color); rgb[2] = Color.blue(color);
                selectedHex[0] = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
                setCircleBg(previewCircle, color);
                // hide all rings, show this one
                for (View rv : swatchViews) if (rv != null) rv.setVisibility(View.INVISIBLE);
                ring.setVisibility(View.VISIBLE);
                syncSliders(root, rgb);
                syncHex(root, rgb);
            });

            paletteRow.addView(swFrame);
        }

        // ── Rainbow gradient strip ──
        GradientView rainbowView = new GradientView(ctx);
        LinearLayout.LayoutParams rainbowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42));
        rainbowLp.setMargins(swMargin, swMargin, swMargin, swMargin);
        rainbowView.setLayoutParams(rainbowLp);
        rainbowView.setOnClickListener(v -> {
            // Pick color from touch position — handled in onTouchEvent in GradientView
        });
        rainbowView.setPickCallback((r, g, b) -> {
            rgb[0] = r; rgb[1] = g; rgb[2] = b;
            selectedHex[0] = String.format("#%02X%02X%02X", r, g, b);
            setCircleBg(previewCircle, Color.rgb(r, g, b));
            for (View rv : swatchViews) if (rv != null) rv.setVisibility(View.INVISIBLE);
            syncSliders(root, rgb);
            syncHex(root, rgb);
        });
        paletteRow.addView(rainbowView);

        paletteScroll.addView(paletteRow);
        root.addView(paletteScroll);

        // ── RGB Sliders ──
        root.addView(makeSectionLabel(ctx, "RGB"));
        addRgbSlider(ctx, root, "R", 0, rgb, previewCircle, selectedHex, () -> syncHex(root, rgb));
        addRgbSlider(ctx, root, "G", 1, rgb, previewCircle, selectedHex, () -> syncHex(root, rgb));
        addRgbSlider(ctx, root, "B", 2, rgb, previewCircle, selectedHex, () -> syncHex(root, rgb));

        // ── Hex input ──
        root.addView(makeSectionLabel(ctx, "HEX CODE"));
        EditText hexInput = new EditText(ctx);
        hexInput.setTag("hex_input");
        hexInput.setTextColor(0xFFFFFFFF);
        hexInput.setHintTextColor(0xFF444444);
        hexInput.setHint("#RRGGBB");
        hexInput.setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
        hexInput.setBackgroundColor(0xFF1E1E1E);
        GradientDrawable hexBg = new GradientDrawable();
        hexBg.setColor(0xFF1E1E1E);
        hexBg.setCornerRadius(dp(ctx, 10));
        hexBg.setStroke(dp(ctx, 1), 0xFF333333);
        hexInput.setBackground(hexBg);
        hexInput.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        hexInput.setTextSize(14);
        hexInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexLp.bottomMargin = dp(ctx, 20);
        hexInput.setLayoutParams(hexLp);
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int count) {
                try {
                    int c = Color.parseColor(s.toString());
                    rgb[0] = Color.red(c); rgb[1] = Color.green(c); rgb[2] = Color.blue(c);
                    selectedHex[0] = s.toString();
                    setCircleBg(previewCircle, c);
                    syncSliders(root, rgb);
                } catch (Exception ignored) {}
            }
        });
        root.addView(hexInput);

        // ── Buttons ──
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF242424, false);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "Apply", 0xFFFFD600, true);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            String hex = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
            callback.onPicked(hex);
            dialog.dismiss();
        });

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.setMarginEnd(dp(ctx, 10));
        cancel.setLayoutParams(cancelLp);
        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(sv);
        dialog.show();
    }

    // ── Gradient rainbow view (touch to pick hue) ─────────────────────────
    public static class GradientView extends View {
        interface PickCallback { void onPick(int r, int g, int b); }
        private PickCallback cb;
        private Paint paint = new Paint();

        public GradientView(Context ctx) { super(ctx); }
        public void setPickCallback(PickCallback c) { this.cb = c; }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            // Rainbow gradient
            int[] colors = {
                0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
                0xFF00FFFF, 0xFF0000FF, 0xFF7F00FF, 0xFFFF00FF, 0xFFFF0000
            };
            LinearGradient grad = new LinearGradient(0, 0, w, 0, colors, null, Shader.TileMode.CLAMP);
            paint.setShader(grad);
            RectF rf = new RectF(0, 0, w, h);
            float r = h / 2f;
            canvas.drawRoundRect(rf, r, r, paint);
            paint.setShader(null);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (cb == null) return true;
            float x = Math.max(0, Math.min(e.getX(), getWidth()));
            float hue = (x / getWidth()) * 360f;
            float[] hsv = {hue, 1f, 1f};
            int c = Color.HSVToColor(hsv);
            cb.onPick(Color.red(c), Color.green(c), Color.blue(c));
            return true;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void addRgbSlider(Context ctx, LinearLayout root, String label, int idx,
                                     int[] rgb, View preview, String[] hexRef, Runnable onUpdate) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(ctx, 8);
        row.setLayoutParams(rowLp);

        int labelColor = idx == 0 ? 0xFFFF5555 : idx == 1 ? 0xFF55CC55 : 0xFF5599FF;

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextColor(labelColor);
        lbl.setTextSize(13);
        lbl.setTypeface(null, android.graphics.Typeface.BOLD);
        lbl.setMinWidth(dp(ctx, 18));
        row.addView(lbl);

        SeekBar seek = new SeekBar(ctx);
        seek.setTag("seek_" + label);
        seek.setMax(255);
        seek.setProgress(rgb[idx]);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(ctx, 32), 1f);
        seekLp.setMarginStart(dp(ctx, 10));
        seekLp.setMarginEnd(dp(ctx, 8));
        seek.setLayoutParams(seekLp);

        // Custom thick track with colour tint
        GradientDrawable track = new GradientDrawable();
        track.setColor(labelColor);
        track.setCornerRadius(dp(ctx, 4));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(labelColor));

        // Custom thumb
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(ctx, 22));
        thumb.setIntrinsicHeight(dp(ctx, 22));
        thumb.getPaint().setColor(0xFFFFD600);
        seek.setThumb(thumb);
        seek.setThumbOffset(0);

        seek.setPadding(0, 0, 0, 0);

        TextView val = new TextView(ctx);
        val.setText(String.valueOf(rgb[idx]));
        val.setTextColor(0xFFAAAAAA);
        val.setTextSize(11);
        val.setMinWidth(dp(ctx, 28));
        val.setGravity(Gravity.END);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                rgb[idx] = p;
                val.setText(String.valueOf(p));
                int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                setCircleBg(preview, color);
                hexRef[0] = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
                onUpdate.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        row.addView(seek);
        row.addView(val);
        root.addView(row);
    }

    private static void syncHex(LinearLayout root, int[] rgb) {
        View v = root.findViewWithTag("hex_input");
        if (v instanceof EditText)
            ((EditText) v).setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
    }

    private static void syncSliders(LinearLayout root, int[] rgb) {
        String[] labels = {"R", "G", "B"};
        for (int i = 0; i < labels.length; i++) {
            View v = root.findViewWithTag("seek_" + labels[i]);
            if (v instanceof SeekBar) ((SeekBar) v).setProgress(rgb[i]);
        }
    }

    private static void setCircleBg(View v, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke(dp(v.getContext(), 2), isLight(color) ? 0xFF444444 : 0xFF666666);
        v.setBackground(gd);
    }

    private static Drawable makeRingDrawable(Context ctx, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(android.graphics.Color.TRANSPARENT);
        gd.setStroke(dp(ctx, 3), color);
        return gd;
    }

    private static boolean isColorMatch(int color, String hex) {
        try {
            return color == Color.parseColor(hex);
        } catch (Exception e) { return false; }
    }

    private static boolean isLight(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum > 128;
    }

    private static TextView makeSectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF666666);
        tv.setTextSize(10);
        tv.setLetterSpacing(0.15f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        lp.topMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView makeBtn(Context ctx, String text, int bgColor, boolean primary) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(primary ? 0xFF000000 : 0xFFCCCCCC);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, 12));
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, 24), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
