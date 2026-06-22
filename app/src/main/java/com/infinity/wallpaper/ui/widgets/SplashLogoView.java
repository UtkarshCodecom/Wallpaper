package com.infinity.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

public class SplashLogoView extends View {

    private Paint strokePaint;
    private Paint glowPaint;
    private Path wPath;
    private PathMeasure pathMeasure;
    private float totalLength;
    private float drawProgress = 0f;   // 0..1 draw-on phase
    private float shimmerOffset = -1f; // 0..1 shimmer phase (starts off-screen)
    private ValueAnimator drawAnimator;
    private ValueAnimator shimmerAnimator;
    private OnAnimationEndListener listener;

    public SplashLogoView(Context context) {
        super(context);
        init();
    }

    public SplashLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SplashLogoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // Main stroke paint — clean white look, thick like Wildcraft logo
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(40f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(Color.WHITE);

        // Soft glow behind it
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(60f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setColor(Color.argb(40, 255, 255, 255));
    }

    /**
     * Must be called after layout so we know width/height.
     */
    private void buildPath() {
        float w = getWidth();
        float h = getHeight();

        float cx = w / 2f;
        float cy = h / 2f;
        float size = Math.min(w, h) * 0.38f; // letter size relative to screen

        // Wildcraft-style 'W': thick and angular but connected (cursive-like single stroke)
        float x0 = cx - size * 0.8f, y0 = cy - size * 0.5f;  // start  (top-left)
        float x1 = cx - size * 0.4f, y1 = cy + size * 0.5f;  // valley 1
        float x2 = cx, y2 = cy - size * 0.1f;  // center rise
        float x3 = cx + size * 0.4f, y3 = cy + size * 0.5f;  // valley 2
        float x4 = cx + size * 0.8f, y4 = cy - size * 0.5f;  // end    (top-right)

        wPath = new Path();
        wPath.moveTo(x0, y0);
        wPath.lineTo(x1, y1);
        wPath.lineTo(x2, y2);
        wPath.lineTo(x3, y3);
        wPath.lineTo(x4, y4);

        pathMeasure = new PathMeasure(wPath, false);
        totalLength = pathMeasure.getLength();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        buildPath();
        startAnimations();
    }

    private void startAnimations() {
        // Phase 1: draw the W over ~900 ms
        drawAnimator = ValueAnimator.ofFloat(0f, 1f);
        drawAnimator.setDuration(900);
        drawAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        drawAnimator.addUpdateListener(a -> {
            drawProgress = (float) a.getAnimatedValue();
            invalidate();
        });

        // Phase 2: shimmer light passes through (starts after draw finishes)
        shimmerAnimator = ValueAnimator.ofFloat(-0.2f, 1.2f);
        shimmerAnimator.setDuration(900);
        shimmerAnimator.setStartDelay(1000);
        shimmerAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        shimmerAnimator.addUpdateListener(a -> {
            shimmerOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // Hold for a moment, then notify
                postDelayed(() -> {
                    if (listener != null) listener.onAnimationEnd();
                }, 400);
            }
        });

        drawAnimator.start();
        shimmerAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wPath == null || totalLength == 0) return;

        // Build partial path up to drawProgress
        Path partial = new Path();
        pathMeasure.getSegment(0, totalLength * drawProgress, partial, true);

        // Glow layer
        canvas.drawPath(partial, glowPaint);

        // Shimmer gradient on stroke paint (only active after draw phase)
        if (shimmerOffset >= -0.2f) {
            float sw = getWidth();
            float x = sw * shimmerOffset;
            float band = sw * 0.25f;

            LinearGradient shimmer = new LinearGradient(
                    x - band, 0, x + band, 0,
                    new int[]{
                            Color.argb(0, 200, 200, 200),
                            Color.argb(255, 255, 255, 255),
                            Color.argb(0, 200, 200, 200)
                    },
                    null,
                    Shader.TileMode.CLAMP
            );
            strokePaint.setShader(shimmer);
        } else {
            strokePaint.setShader(null);
            strokePaint.setColor(Color.WHITE);
        }

        // Main stroke
        canvas.drawPath(partial, strokePaint);
    }

    public void setOnAnimationEndListener(OnAnimationEndListener l) {
        this.listener = l;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (drawAnimator != null) drawAnimator.cancel();
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        super.onDetachedFromWindow();
    }

    public interface OnAnimationEndListener {
        void onAnimationEnd();
    }
}