package com.infinity.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

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

        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int cx = w / 2;
        int cy = h / 2;
        int r = Math.min(w, h) / 2 - strokeWidth;
        if (r <= 0) return;

        path.reset();

        int waveCount = 40; // local variable
        int steps = Math.max(120, waveCount * 3);
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / (float) steps; // 0..1
            float angle = t * (float) (Math.PI * 2);
            // sinusoidal perturbation around circle radius
            float wave = (float) Math.sin(waveCount * angle + phase);
            float amp = r * 0.06f; // amplitude relative to radius
            float rr = r + amp * wave;
            float x = cx + rr * (float) Math.cos(angle);
            float y = cy + rr * (float) Math.sin(angle);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        // draw base dark behind (semi-transparent black)
        Paint bg = new Paint(paint);
        bg.setColor(Color.parseColor("#55000000"));
        canvas.drawPath(path, bg);

        // draw foreground (accent red)
        canvas.drawPath(path, paint);
    }

}
