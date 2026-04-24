package com.walle.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class ZigzagProgressView extends View {
    private final Paint paint;
    private final Path path;
    private final ValueAnimator animator;
    private final int strokeWidth = 12;
    private float phase = 0f; // animation phase

    public ZigzagProgressView(Context context) {
        super(context);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();
        animator = ValueAnimator.ofFloat(0f, (float) Math.PI * 2f);
        init();
    }

    public ZigzagProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();
        animator = ValueAnimator.ofFloat(0f, (float) Math.PI * 2f);
        init();
    }

    public ZigzagProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();
        animator = ValueAnimator.ofFloat(0f, (float) Math.PI * 2f);
        init();
    }

    private void init() {
        paint.setColor(Color.parseColor("#D84040")); // accent red
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        animator.setDuration(1200);
        animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animator.cancel();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int cx = w / 2;
        int cy = h / 2;
        int r = Math.min(w, h) / 2 - strokeWidth;
        if (r <= 0) return;

        path.reset();

        // 8-sided curved polygon
        int numSides = 8;
        float baseRadius = r * 0.8f;
        float bumpRadius = r * 0.15f;
        int steps = 120;

        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            float angle = t * (float) (Math.PI * 2);
            // Add phase so it rotates
            float rotatedAngle = angle + phase;
            // The "curved" bump
            float rr = baseRadius + bumpRadius * (float) Math.sin(numSides * rotatedAngle);
            float x = cx + rr * (float) Math.cos(rotatedAngle);
            float y = cy + rr * (float) Math.sin(rotatedAngle);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        // draw background faint shape
        Paint bgPaint = new Paint(paint);
        bgPaint.setColor(Color.parseColor("#33D84040")); // faint red
        canvas.drawPath(path, bgPaint);

        // Calculate snake portion
        float length = new android.graphics.PathMeasure(path, false).getLength();
        android.graphics.Path snakePath = new Path();
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(path, false);

        // Snake moves along the path
        float snakeLength = length * 0.25f; // 25% of path
        float offset = (phase / (float) (Math.PI * 2)) * length;

        float startD = offset;
        float stopD = startD + snakeLength;

        if (stopD <= length) {
            pm.getSegment(startD, stopD, snakePath, true);
        } else {
            pm.getSegment(startD, length, snakePath, true);
            pm.getSegment(0, stopD - length, snakePath, true);
        }

        canvas.drawPath(snakePath, paint);
    }
}
