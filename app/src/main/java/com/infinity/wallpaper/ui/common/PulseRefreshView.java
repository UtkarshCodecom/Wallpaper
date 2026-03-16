package com.infinity.wallpaper.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom circular rotating refresh indicator with yellow/pink gradient
 */
public class PulseRefreshView extends View {

    private Paint arcPaint;
    private Paint glowPaint;
    private RectF arcRect;
    private float rotationAngle = 0f;
    private float arcSweep = 270f;
    private ValueAnimator rotateAnimator;
    private ValueAnimator sweepAnimator;

    private final int colorYellow = 0xFFD84040;    // Accent red
    private final int colorPink = 0xFF8E1616;      // Dark red
    private final int colorWhite = 0xFFEEEEEE;     // Light text

    private float strokeWidth = 4f;
    private float radius = 20f;

    public PulseRefreshView(Context context) {
        super(context);
        init();
    }

    public PulseRefreshView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PulseRefreshView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        strokeWidth = 3.5f * density;
        radius = 16f * density;

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(strokeWidth + 4 * density);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setAlpha(80);

        arcRect = new RectF();

        setupAnimators();
    }

    private void setupAnimators() {
        // Rotation animator - continuous 360 degree rotation
        rotateAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotateAnimator.setDuration(1200);
        rotateAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotateAnimator.setInterpolator(new LinearInterpolator());
        rotateAnimator.addUpdateListener(animation -> {
            rotationAngle = (float) animation.getAnimatedValue();
            invalidate();
        });

        // Sweep animator - arc length pulsing
        sweepAnimator = ValueAnimator.ofFloat(90f, 270f);
        sweepAnimator.setDuration(800);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setRepeatMode(ValueAnimator.REVERSE);
        sweepAnimator.addUpdateListener(animation -> {
            arcSweep = (float) animation.getAnimatedValue();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float centerX = w / 2f;
        float centerY = h / 2f;
        float padding = strokeWidth + 4;
        arcRect.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
        );

        // Create gradient shader
        SweepGradient gradient = new SweepGradient(
                centerX, centerY,
                new int[]{colorYellow, colorPink, colorWhite, colorYellow},
                new float[]{0f, 0.33f, 0.66f, 1f}
        );
        arcPaint.setShader(gradient);
        glowPaint.setShader(gradient);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    public void startAnimation() {
        if (rotateAnimator != null && !rotateAnimator.isRunning()) {
            rotateAnimator.start();
        }
        if (sweepAnimator != null && !sweepAnimator.isRunning()) {
            sweepAnimator.start();
        }
    }

    public void stopAnimation() {
        if (rotateAnimator != null) {
            rotateAnimator.cancel();
        }
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        canvas.save();
        canvas.rotate(rotationAngle, getWidth() / 2f, getHeight() / 2f);

        // Draw glow effect
        canvas.drawArc(arcRect, 0, arcSweep, false, glowPaint);

        // Draw main arc
        canvas.drawArc(arcRect, 0, arcSweep, false, arcPaint);

        canvas.restore();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int desiredSize = (int) ((radius * 2) + strokeWidth * 2 + 16 * density);

        int width = resolveSize(desiredSize, widthMeasureSpec);
        int height = resolveSize(desiredSize, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }
}
