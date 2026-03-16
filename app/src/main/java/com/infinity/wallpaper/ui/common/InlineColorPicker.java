package com.infinity.wallpaper.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Inline color picker - horizontal scroll with color swatches,
 * rainbow gradient, and custom color option at the end.
 */
public class InlineColorPicker extends HorizontalScrollView {

    public interface OnColorSelectedListener {
        void onColorSelected(String hexColor);
    }

    // Curated palette
    private static final int[] PALETTE = {
            // Whites / Greys / Blacks
            0xFFFFFFFF, 0xFFF5F5F5, 0xFFEEEEEE, 0xFFCCCCCC,
            0xFFAAAAAA, 0xFF888888, 0xFF555555, 0xFF333333,
            0xFF1E1E1E, 0xFF000000,
            // Yellows / Golds
            0xFFFFD600, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF8F00,
            0xFFFF6D00, 0xFFE65100,
            // Pinks / Reds
            0xFFFF5FA2, 0xFFFF6EC7, 0xFFFF4081, 0xFFE91E63,
            0xFFFF4444, 0xFFD50000, 0xFFB71C1C,
            // Purples
            0xFF9C27B0, 0xFF7B1FA2, 0xFF651FFF, 0xFF512DA8,
            0xFF9575CD,
            // Blues
            0xFF2196F3, 0xFF1976D2, 0xFF0D47A1, 0xFF00BCD4,
            0xFF00B0FF, 0xFF90CAF9,
            // Greens
            0xFF4CAF50, 0xFF388E3C, 0xFF00E676, 0xFF76FF03,
            // Teals
            0xFF009688, 0xFF00BFA5, 0xFF26A69A,
    };

    private LinearLayout container;
    private OnColorSelectedListener listener;
    private String selectedColor = "#FFFFFF";
    private View[] ringViews;
    private View customRing;
    private View customSwatch;

    public InlineColorPicker(Context context) {
        super(context);
        init(context);
    }

    public InlineColorPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public InlineColorPicker(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context ctx) {
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);

        container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(4), dp(4), dp(4), dp(4));
        addView(container);

        ringViews = new View[PALETTE.length];
        int swSize = dp(32);
        int margin = dp(4);

        // Add color swatches
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            final int idx = i;

            // Item container: swatch + label
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(swSize + dp(8), LinearLayout.LayoutParams.WRAP_CONTENT);
            itemLp.setMargins(margin, 0, margin, 0);
            item.setLayoutParams(itemLp);

            FrameLayout frame = new FrameLayout(ctx);
            LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(swSize + dp(4), swSize + dp(4));
            frame.setLayoutParams(frameLp);

            View swatch = new View(ctx);
            swatch.setLayoutParams(new FrameLayout.LayoutParams(swSize, swSize, Gravity.CENTER));
            setCircleBg(swatch, color);
            frame.addView(swatch);

            View ring = new View(ctx);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(swSize + dp(4), swSize + dp(4), Gravity.CENTER);
            ring.setLayoutParams(ringLp);
            ring.setBackground(makeRingDrawable(0xFFFFD600));
            ring.setVisibility(INVISIBLE);
            frame.addView(ring);
            ringViews[i] = ring;

            // Label under the swatch (number)
            TextView label = new TextView(ctx);
            label.setTextColor(0xFFEEEEEE);
            label.setTextSize(10);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);
            label.setText(String.valueOf(idx + 1));

            item.addView(frame);
            item.addView(label);

            item.setOnClickListener(v -> selectColor(color, idx));

            container.addView(item);
        }

        // Add custom color picker button (solid color)
        LinearLayout customItem = new LinearLayout(ctx);
        customItem.setOrientation(LinearLayout.VERTICAL);
        customItem.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams customItemLp = new LinearLayout.LayoutParams(swSize + dp(8), LinearLayout.LayoutParams.WRAP_CONTENT);
        customItemLp.setMargins(margin, 0, margin, 0);
        customItem.setLayoutParams(customItemLp);

        FrameLayout customFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(swSize + dp(4), swSize + dp(4));
        customFrame.setLayoutParams(customLp);

        customSwatch = new View(ctx);
        customSwatch.setLayoutParams(new FrameLayout.LayoutParams(swSize, swSize, Gravity.CENTER));
        GradientDrawable customBg = new GradientDrawable();
        customBg.setShape(GradientDrawable.OVAL);
        customBg.setColor(0xFF444444);
        customBg.setStroke(dp(1), 0xFF666666);
        customSwatch.setBackground(customBg);
        customFrame.addView(customSwatch);

        // Plus icon overlay for custom color
        TextView plusIcon = new TextView(ctx);
        plusIcon.setText("+");
        plusIcon.setTextColor(0xFFFFFFFF);
        plusIcon.setTextSize(14);
        plusIcon.setGravity(Gravity.CENTER);
        plusIcon.setLayoutParams(new FrameLayout.LayoutParams(swSize, swSize, Gravity.CENTER));
        customFrame.addView(plusIcon);

        customRing = new View(ctx);
        FrameLayout.LayoutParams customRingLp = new FrameLayout.LayoutParams(swSize + dp(4), swSize + dp(4), Gravity.CENTER);
        customRing.setLayoutParams(customRingLp);
        customRing.setBackground(makeRingDrawable(0xFFFFD600));
        customRing.setVisibility(INVISIBLE);
        customFrame.addView(customRing);

        TextView customLabel = new TextView(ctx);
        customLabel.setTextColor(0xFFEEEEEE);
        customLabel.setTextSize(10);
        customLabel.setGravity(Gravity.CENTER);
        customLabel.setPadding(0, dp(2), 0, 0);
        customLabel.setText("CUSTOM");

        customItem.addView(customFrame);
        customItem.addView(customLabel);

        customItem.setOnClickListener(v -> showAdvancedColorDialog(ctx));
        container.addView(customItem);
    }

    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelectedColor(String hexColor) {
        this.selectedColor = hexColor;
        try {
            int color = Color.parseColor(hexColor);
            // Update custom swatch preview to current color
            if (customSwatch != null) {
                setCircleBg(customSwatch, color);
            }
            // Check if it matches any palette color
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i] == color || (PALETTE[i] & 0xFFFFFF) == (color & 0xFFFFFF)) {
                    hideAllRings();
                    if (ringViews[i] != null) ringViews[i].setVisibility(VISIBLE);
                    return;
                }
            }
            // Custom color - show ring on custom button
            hideAllRings();
            if (customRing != null) customRing.setVisibility(VISIBLE);
        } catch (Exception ignored) {}
    }

    private void selectColor(int color, int index) {
        hideAllRings();
        if (ringViews[index] != null) ringViews[index].setVisibility(VISIBLE);
        selectedColor = String.format("#%06X", (0xFFFFFF & color));
        if (customSwatch != null) setCircleBg(customSwatch, color);
        if (listener != null) listener.onColorSelected(selectedColor);
    }

    private void hideAllRings() {
        for (View ring : ringViews) {
            if (ring != null) ring.setVisibility(INVISIBLE);
        }
        if (customRing != null) customRing.setVisibility(INVISIBLE);
    }

    /** Advanced color picker - compact HTML-style with square SV picker and hue bar */
    private void showAdvancedColorDialog(Context ctx) {
        // Parse current color
        int initColor = 0xFFFFFFFF;
        int initAlpha = 255;
        try {
            initColor = Color.parseColor(selectedColor);
            initAlpha = Color.alpha(initColor);
        } catch (Exception ignored) {}

        final float[] hsv = new float[3];
        Color.colorToHSV(initColor | 0xFF000000, hsv);
        final int[] alpha = {initAlpha};

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF222222);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Title row with preview circle
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleRowLp.bottomMargin = dp(12);
        titleRow.setLayoutParams(titleRowLp);

        TextView title = new TextView(ctx);
        title.setText("Color");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        // Circular preview swatch (small)
        View preview = new View(ctx);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        preview.setLayoutParams(prevLp);
        updateCirclePreview(preview, hsv, alpha[0]);
        titleRow.addView(preview);
        root.addView(titleRow);

        // Square SV picker (saturation X, brightness Y) - HTML style
        final int squareSize = dp(200);
        FrameLayout svContainer = new FrameLayout(ctx);
        LinearLayout.LayoutParams svContLp = new LinearLayout.LayoutParams(squareSize, squareSize);
        svContLp.gravity = Gravity.CENTER_HORIZONTAL;
        svContLp.bottomMargin = dp(12);
        svContainer.setLayoutParams(svContLp);

        // Background: white-to-currentHue gradient (horizontal)
        View satLayer = new View(ctx);
        satLayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        svContainer.addView(satLayer);

        // Overlay: transparent-to-black gradient (vertical)
        View valLayer = new View(ctx);
        valLayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable blackGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xFF000000}
        );
        valLayer.setBackground(blackGrad);
        svContainer.addView(valLayer);

        // Cursor indicator
        View cursor = new View(ctx);
        int cursorSize = dp(12);
        FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(cursorSize, cursorSize);
        cursor.setLayoutParams(cursorLp);
        GradientDrawable cursorBg = new GradientDrawable();
        cursorBg.setShape(GradientDrawable.OVAL);
        cursorBg.setStroke(dp(2), 0xFFFFFFFF);
        cursorBg.setColor(0x00000000);
        cursor.setBackground(cursorBg);
        svContainer.addView(cursor);

        // Update SV square background based on current hue
        Runnable updateSvBackground = () -> {
            int hueColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            GradientDrawable satGrad = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFFFFFFFF, hueColor}
            );
            satLayer.setBackground(satGrad);
        };
        updateSvBackground.run();

        // Update cursor position
        Runnable updateCursor = () -> {
            int x = (int)(hsv[1] * squareSize) - cursorSize / 2;
            int y = (int)((1f - hsv[2]) * squareSize) - cursorSize / 2;
            cursorLp.leftMargin = Math.max(0, Math.min(squareSize - cursorSize, x));
            cursorLp.topMargin = Math.max(0, Math.min(squareSize - cursorSize, y));
            cursor.setLayoutParams(cursorLp);
        };
        updateCursor.run();

        // Touch handler for SV square
        svContainer.setOnTouchListener((v, event) -> {
            float x = Math.max(0, Math.min(squareSize, event.getX()));
            float y = Math.max(0, Math.min(squareSize, event.getY()));
            hsv[1] = x / squareSize;  // saturation
            hsv[2] = 1f - (y / squareSize);  // brightness (inverted Y)
            updateCursor.run();
            updateCirclePreview(preview, hsv, alpha[0]);
            return true;
        });
        root.addView(svContainer);

        // Hue bar (horizontal rainbow)
        View hueBar = new View(ctx);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(squareSize, dp(24));
        hueLp.gravity = Gravity.CENTER_HORIZONTAL;
        hueLp.bottomMargin = dp(10);
        hueBar.setLayoutParams(hueLp);
        GradientDrawable hueGrad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}
        );
        hueGrad.setCornerRadius(dp(4));
        hueBar.setBackground(hueGrad);
        hueBar.setOnTouchListener((v, event) -> {
            float x = Math.max(0, Math.min(v.getWidth(), event.getX()));
            hsv[0] = (x / v.getWidth()) * 360f;
            updateSvBackground.run();
            updateCirclePreview(preview, hsv, alpha[0]);
            return true;
        });
        root.addView(hueBar);

        // Opacity bar
        LinearLayout alphaRow = new LinearLayout(ctx);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alphaRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alphaRowLp.bottomMargin = dp(12);
        alphaRow.setLayoutParams(alphaRowLp);

        TextView alphaLabel = new TextView(ctx);
        alphaLabel.setText("A");
        alphaLabel.setTextColor(0xFF888888);
        alphaLabel.setTextSize(12);
        alphaLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT));
        alphaRow.addView(alphaLabel);

        SeekBar alphaSeek = new SeekBar(ctx);
        alphaSeek.setMax(255);
        alphaSeek.setProgress(alpha[0]);
        alphaSeek.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alpha[0] = progress;
                updateCirclePreview(preview, hsv, alpha[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        alphaRow.addView(alphaSeek);

        TextView alphaVal = new TextView(ctx);
        alphaVal.setText(String.valueOf(alpha[0]));
        alphaVal.setTextColor(0xFFFFD600);
        alphaVal.setTextSize(11);
        alphaVal.setLayoutParams(new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
        alphaVal.setGravity(Gravity.END);
        alphaRow.addView(alphaVal);

        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alpha[0] = progress;
                alphaVal.setText(String.valueOf(progress));
                updateCirclePreview(preview, hsv, alpha[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(alphaRow);

        // Buttons
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF444444);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "OK", 0xFFFFD600);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            int color = Color.HSVToColor(alpha[0], hsv);
            String hex;
            if (alpha[0] < 255) {
                hex = String.format("#%02X%06X", alpha[0], (color & 0xFFFFFF));
            } else {
                hex = String.format("#%06X", (color & 0xFFFFFF));
            }
            hideAllRings();
            if (customRing != null) customRing.setVisibility(VISIBLE);
            selectedColor = hex;
            if (listener != null) listener.onColorSelected(hex);
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(dp(8));
        cancel.setLayoutParams(btnLp);

        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private void updateCirclePreview(View preview, float[] hsv, int alpha) {
        int color = Color.HSVToColor(alpha, hsv);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        if (Color.alpha(color) < 50 || (hsv[2] > 0.9f && hsv[1] < 0.1f)) {
            gd.setStroke(dp(1), 0xFF444444);
        }
        preview.setBackground(gd);
    }

    private void showCustomColorDialog(Context ctx) {
        int[] rgb = {255, 255, 255};
        try {
            int c = Color.parseColor(selectedColor);
            rgb[0] = Color.red(c);
            rgb[1] = Color.green(c);
            rgb[2] = Color.blue(c);
        } catch (Exception ignored) {}

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        // Title
        TextView title = new TextView(ctx);
        title.setText("Custom Color");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // Preview
        View preview = new View(ctx);
        preview.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(60)));
        setCircleBg(preview, Color.rgb(rgb[0], rgb[1], rgb[2]));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        prevLp.gravity = Gravity.CENTER_HORIZONTAL;
        prevLp.bottomMargin = dp(16);
        preview.setLayoutParams(prevLp);
        root.addView(preview);

        // RGB Sliders
        SeekBar[] seekBars = new SeekBar[3];
        String[] labels = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);
            row.setLayoutParams(rowLp);

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextColor(0xFFAAAAAA);
            label.setTextSize(14);
            label.setLayoutParams(new LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(label);

            SeekBar seek = new SeekBar(ctx);
            seek.setMax(255);
            seek.setProgress(rgb[i]);
            LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            seek.setLayoutParams(seekLp);
            seekBars[i] = seek;

            TextView valTv = new TextView(ctx);
            valTv.setText(String.valueOf(rgb[i]));
            valTv.setTextColor(0xFFFFD600);
            valTv.setTextSize(12);
            valTv.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
            valTv.setGravity(Gravity.END);

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[idx] = progress;
                    valTv.setText(String.valueOf(progress));
                    setCircleBg(preview, Color.rgb(rgb[0], rgb[1], rgb[2]));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            row.addView(seek);
            row.addView(valTv);
            root.addView(row);
        }

        // Hex input
        EditText hexInput = new EditText(ctx);
        hexInput.setTextColor(0xFFFFFFFF);
        hexInput.setHintTextColor(0xFF555555);
        hexInput.setHint("#RRGGBB");
        hexInput.setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
        hexInput.setBackgroundColor(0xFF2A2A2A);
        hexInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        hexInput.setTextSize(14);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexLp.topMargin = dp(12);
        hexLp.bottomMargin = dp(20);
        hexInput.setLayoutParams(hexLp);
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    int c = Color.parseColor(s.toString());
                    rgb[0] = Color.red(c);
                    rgb[1] = Color.green(c);
                    rgb[2] = Color.blue(c);
                    setCircleBg(preview, c);
                    for (int i = 0; i < 3; i++) {
                        seekBars[i].setProgress(rgb[i]);
                    }
                } catch (Exception ignored) {}
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(hexInput);

        // Buttons
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF333333);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "Apply", 0xFFFFD600);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            String hex = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
            hideAllRings();
            if (customRing != null) customRing.setVisibility(VISIBLE);
            selectedColor = hex;
            if (listener != null) listener.onColorSelected(hex);
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(dp(10));
        cancel.setLayoutParams(btnLp);

        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private void showGradientDialog(Context ctx) {
        final int[] color1 = {0xFFFF5FA2}; // Pink
        final int[] color2 = {0xFFFFD600}; // Yellow
        final int[] angle = {0}; // 0 = left to right

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        // Title
        TextView title = new TextView(ctx);
        title.setText("Linear Gradient");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // Gradient Preview
        View gradPreview = new View(ctx);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        prevLp.gravity = Gravity.CENTER_HORIZONTAL;
        prevLp.bottomMargin = dp(20);
        gradPreview.setLayoutParams(prevLp);
        updateGradPreview(gradPreview, color1[0], color2[0], angle[0]);
        root.addView(gradPreview);

        // Color 1 label and swatch
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row1Lp.bottomMargin = dp(12);
        row1.setLayoutParams(row1Lp);

        TextView lbl1 = new TextView(ctx);
        lbl1.setText("Color 1");
        lbl1.setTextColor(0xFFAAAAAA);
        lbl1.setTextSize(14);
        lbl1.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(lbl1);

        View swatch1 = new View(ctx);
        swatch1.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        setCircleBg(swatch1, color1[0]);
        swatch1.setOnClickListener(v -> showColorPickerForGradient(ctx, color1[0], picked -> {
            color1[0] = picked;
            setCircleBg(swatch1, picked);
            updateGradPreview(gradPreview, color1[0], color2[0], angle[0]);
        }));
        row1.addView(swatch1);
        root.addView(row1);

        // Color 2 label and swatch
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.bottomMargin = dp(16);
        row2.setLayoutParams(row2Lp);

        TextView lbl2 = new TextView(ctx);
        lbl2.setText("Color 2");
        lbl2.setTextColor(0xFFAAAAAA);
        lbl2.setTextSize(14);
        lbl2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(lbl2);

        View swatch2 = new View(ctx);
        swatch2.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        setCircleBg(swatch2, color2[0]);
        swatch2.setOnClickListener(v -> showColorPickerForGradient(ctx, color2[0], picked -> {
            color2[0] = picked;
            setCircleBg(swatch2, picked);
            updateGradPreview(gradPreview, color1[0], color2[0], angle[0]);
        }));
        row2.addView(swatch2);
        root.addView(row2);

        // Angle slider
        TextView angleLbl = new TextView(ctx);
        angleLbl.setText("Angle: 0°");
        angleLbl.setTextColor(0xFFAAAAAA);
        angleLbl.setTextSize(14);
        LinearLayout.LayoutParams angleLblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        angleLblLp.bottomMargin = dp(4);
        angleLbl.setLayoutParams(angleLblLp);
        root.addView(angleLbl);

        SeekBar angleSeek = new SeekBar(ctx);
        angleSeek.setMax(360);
        angleSeek.setProgress(0);
        LinearLayout.LayoutParams angleSeekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        angleSeekLp.bottomMargin = dp(20);
        angleSeek.setLayoutParams(angleSeekLp);
        angleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                angle[0] = progress;
                angleLbl.setText("Angle: " + progress + "°");
                updateGradPreview(gradPreview, color1[0], color2[0], angle[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(angleSeek);

        // Buttons
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF333333);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "Apply", 0xFFFFD600);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            // Return gradient as "gradient:color1:color2:angle" format
            String gradientValue = String.format("gradient:%08X:%08X:%d", color1[0], color2[0], angle[0]);
            hideAllRings();
            selectedColor = gradientValue;
            if (listener != null) listener.onColorSelected(gradientValue);
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(dp(10));
        cancel.setLayoutParams(btnLp);

        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private void updateGradPreview(View preview, int c1, int c2, int angleDeg) {
        GradientDrawable.Orientation orient = getGradientOrientation(angleDeg);
        GradientDrawable grad = new GradientDrawable(orient, new int[]{c1, c2});
        grad.setCornerRadius(dp(12));
        preview.setBackground(grad);
    }

    private GradientDrawable.Orientation getGradientOrientation(int angle) {
        // Normalize angle to 0-360
        angle = angle % 360;
        if (angle < 0) angle += 360;

        if (angle >= 337 || angle < 23) return GradientDrawable.Orientation.LEFT_RIGHT;
        if (angle >= 23 && angle < 67) return GradientDrawable.Orientation.TL_BR;
        if (angle >= 67 && angle < 113) return GradientDrawable.Orientation.TOP_BOTTOM;
        if (angle >= 113 && angle < 157) return GradientDrawable.Orientation.TR_BL;
        if (angle >= 157 && angle < 203) return GradientDrawable.Orientation.RIGHT_LEFT;
        if (angle >= 203 && angle < 247) return GradientDrawable.Orientation.BR_TL;
        if (angle >= 247 && angle < 293) return GradientDrawable.Orientation.BOTTOM_TOP;
        return GradientDrawable.Orientation.BL_TR;
    }

    interface GradientColorCallback {
        void onColorPicked(int color);
    }

    private void showColorPickerForGradient(Context ctx, int initialColor, GradientColorCallback callback) {
        int[] rgb = {Color.red(initialColor), Color.green(initialColor), Color.blue(initialColor)};

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView title = new TextView(ctx);
        title.setText("Pick Color");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(12);
        title.setLayoutParams(titleLp);
        root.addView(title);

        View preview = new View(ctx);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        prevLp.gravity = Gravity.CENTER_HORIZONTAL;
        prevLp.bottomMargin = dp(12);
        preview.setLayoutParams(prevLp);
        setCircleBg(preview, Color.rgb(rgb[0], rgb[1], rgb[2]));
        root.addView(preview);

        String[] labels = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(6);
            row.setLayoutParams(rowLp);

            TextView lbl = new TextView(ctx);
            lbl.setText(labels[i]);
            lbl.setTextColor(0xFFAAAAAA);
            lbl.setTextSize(13);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(lbl);

            SeekBar seek = new SeekBar(ctx);
            seek.setMax(255);
            seek.setProgress(rgb[i]);
            LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            seek.setLayoutParams(seekLp);

            TextView valTv = new TextView(ctx);
            valTv.setText(String.valueOf(rgb[i]));
            valTv.setTextColor(0xFFFFD600);
            valTv.setTextSize(11);
            valTv.setLayoutParams(new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));
            valTv.setGravity(Gravity.END);

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[idx] = progress;
                    valTv.setText(String.valueOf(progress));
                    setCircleBg(preview, Color.rgb(rgb[0], rgb[1], rgb[2]));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            row.addView(seek);
            row.addView(valTv);
            root.addView(row);
        }

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(12);
        btnRow.setLayoutParams(btnRowLp);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF333333);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "OK", 0xFFFFD600);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            callback.onColorPicked(Color.rgb(rgb[0], rgb[1], rgb[2]));
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(dp(8));
        cancel.setLayoutParams(btnLp);

        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }


    private void showGrayscaleDialog(Context ctx) {
        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        // Title
        TextView title = new TextView(ctx);
        title.setText("Grayscale Color");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // Preview swatch
        View preview = new View(ctx);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        prevLp.gravity = Gravity.CENTER_HORIZONTAL;
        prevLp.bottomMargin = dp(16);
        preview.setLayoutParams(prevLp);
        setCircleBg(preview, 0xFF808080);  // mid-gray initial
        root.addView(preview);

        // Rectangular grayscale gradient picker
        // User can tap anywhere on this rect to pick a grayscale value
        final int[] selectedGray = {128};  // 0-255
        FrameLayout pickerFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams pickerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200));
        pickerLp.bottomMargin = dp(20);
        pickerFrame.setLayoutParams(pickerLp);

        View gradientRect = new View(ctx);
        gradientRect.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable grayGrad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF000000, 0xFFFFFFFF}  // black to white
        );
        gradientRect.setBackground(grayGrad);

        // Touch listener to pick color from gradient
        gradientRect.setOnTouchListener((v, event) -> {
            float x = event.getX();
            float width = v.getWidth();
            float ratio = Math.max(0, Math.min(1, x / width));
            selectedGray[0] = Math.round(ratio * 255);
            int color = Color.rgb(selectedGray[0], selectedGray[0], selectedGray[0]);
            setCircleBg(preview, color);
            return true;
        });

        pickerFrame.addView(gradientRect);
        root.addView(pickerFrame);

        // Value display (0-255)
        TextView valLabel = new TextView(ctx);
        valLabel.setText("Value: " + selectedGray[0]);
        valLabel.setTextColor(0xFFFFD600);
        valLabel.setTextSize(14);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valLp.gravity = Gravity.CENTER_HORIZONTAL;
        valLp.bottomMargin = dp(16);
        valLabel.setLayoutParams(valLp);
        root.addView(valLabel);

        // Buttons
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancel = makeBtn(ctx, "Cancel", 0xFF333333);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView apply = makeBtn(ctx, "Apply", 0xFFFFD600);
        apply.setTextColor(0xFF000000);
        apply.setOnClickListener(v -> {
            String hex = String.format("#%02X%02X%02X", selectedGray[0], selectedGray[0], selectedGray[0]);
            hideAllRings();
            if (customRing != null) customRing.setVisibility(VISIBLE);
            selectedColor = hex;
            if (listener != null) listener.onColorSelected(hex);
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(dp(10));
        cancel.setLayoutParams(btnLp);

        btnRow.addView(cancel);
        btnRow.addView(apply);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private TextView makeBtn(Context ctx, String text, int bgColor) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(14);
        btn.setPadding(dp(24), dp(12), dp(24), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    private void setCircleBg(View v, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        if (color == 0xFFFFFFFF || Color.alpha(color) < 50) {
            gd.setStroke(dp(1), 0xFF444444);
        }
        v.setBackground(gd);
    }

    private GradientDrawable makeRingDrawable(int color) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), color);
        return ring;
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }
}
