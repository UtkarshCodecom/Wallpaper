package com.infinity.wallpaper.ui.widgets;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView that behaves like CENTER_CROP but left-aligned (reveals more content to the right as width increases).
 * This avoids the "zoom" feeling during width animations.
 */
public class LeftCropImageView extends AppCompatImageView {

    private final Matrix drawMatrix = new Matrix();

    public LeftCropImageView(@NonNull Context context) {
        super(context);
        init();
    }

    public LeftCropImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LeftCropImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMatrix();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        updateMatrix();
    }

    private void updateMatrix() {
        Drawable d = getDrawable();
        if (d == null) return;

        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (vw <= 0 || vh <= 0) return;

        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        float scale = Math.max(vw / (float) dw, vh / (float) dh);

        // float scaledW = dw * scale; // not needed
        float scaledH = dh * scale;

        // Left align horizontally (dx=0), center vertically.
        float dx = 0f;
        float dy = (vh - scaledH) * 0.5f;

        drawMatrix.reset();
        drawMatrix.postScale(scale, scale);
        drawMatrix.postTranslate(dx + getPaddingLeft(), dy + getPaddingTop());

        setImageMatrix(drawMatrix);
        invalidate();
    }
}
