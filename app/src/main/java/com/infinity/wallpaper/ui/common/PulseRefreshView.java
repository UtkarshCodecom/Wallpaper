package com.infinity.wallpaper.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom morphing shape-changing loader
 * Smoothly transitions between different geometric shapes
 */
public class PulseRefreshView extends View {

    // Shape types: Circle, Square, Triangle, Pentagon, Hexagon, Star
    private static final int SHAPE_CIRCLE = 0;
    private static final int SHAPE_SQUARE = 1;
    private static final int SHAPE_TRIANGLE = 2;
    private static final int SHAPE_PENTAGON = 3;
    private static final int SHAPE_HEXAGON = 4;
    private static final int SHAPE_STAR = 5;
    private static final int TOTAL_SHAPES = 6;
    // Colors
    private final int colorOuter = 0xFF6B4C9A;      // Purple/violet
    private final int colorInner = 0xFFE8D5FF;      // Light purple/white
    private Paint shapePaint;
    private Paint innerShapePaint;
    private Path currentPath;
    private Path innerPath;
    private ValueAnimator morphAnimator;
    private ValueAnimator rotationAnimator;
    private float morphProgress = 0f;
    private float rotationAngle = 0f;
    private int currentShapeIndex = 0;
    private float size = 10f;
    private float centerX, centerY;
    private float pullProgress = 0f;

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
        size = 50f * density;

        // Outer shape paint (stroke)
        shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(4f * density);
        shapePaint.setColor(colorOuter);
        shapePaint.setStrokeCap(Paint.Cap.ROUND);
        shapePaint.setStrokeJoin(Paint.Join.ROUND);

        // Inner shape paint (filled)
        innerShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerShapePaint.setStyle(Paint.Style.FILL);
        innerShapePaint.setColor(colorInner);

        currentPath = new Path();
        innerPath = new Path();

        setupAnimators();
    }

    private void setupAnimators() {
        // Morph animation - transitions between shapes
        morphAnimator = ValueAnimator.ofFloat(0f, 1f);
        morphAnimator.setDuration(700);
        morphAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        morphAnimator.addUpdateListener(animation -> {
            morphProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        morphAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                currentShapeIndex = (currentShapeIndex + 1) % TOTAL_SHAPES;
                morphAnimator.start(); // Continue to next shape
            }
        });

        // Rotation animation - smooth continuous rotation
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(2000);
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            rotationAngle = (float) animation.getAnimatedValue();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Animation is controlled externally (e.g., pull-to-refresh release).
        // Don't auto-start here.
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    /**
     * Called while the user is pulling (0..1). This should NOT start the full animation;
     * it just gives visual feedback before release.
     */
    public void setPullProgress(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        if (pullProgress == clamped) return;
        pullProgress = clamped;

        // Simple, cheap feedback: fade + slight scale-in.
        float alpha = 0.15f + 0.85f * pullProgress;
        setAlpha(alpha);
        float scale = 0.85f + 0.15f * pullProgress;
        setScaleX(scale);
        setScaleY(scale);

        invalidate();
    }

    public void startAnimation() {
        // Ensure fully visible when refreshing
        setPullProgress(1f);
        if (morphAnimator != null && !morphAnimator.isRunning()) {
            morphAnimator.start();
        }
        if (rotationAnimator != null && !rotationAnimator.isRunning()) {
            rotationAnimator.start();
        }
    }

    public void stopAnimation() {
        if (morphAnimator != null) {
            morphAnimator.cancel();
        }
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        canvas.save();
        canvas.rotate(rotationAngle, centerX, centerY);

        // Get current and next shapes
        int nextShapeIndex = (currentShapeIndex + 1) % TOTAL_SHAPES;

        // Morph between current and next shape
        Path outerMorphed = morphShapes(currentShapeIndex, nextShapeIndex, morphProgress, size * 0.35f);
        Path innerMorphed = morphShapes(currentShapeIndex, nextShapeIndex, morphProgress, size * 0.35f);

        // Draw outer shape (stroke)
        canvas.drawPath(outerMorphed, shapePaint);

        // Draw inner shape (filled)
        canvas.drawPath(innerMorphed, innerShapePaint);

        canvas.restore();
    }

    /**
     * Morph between two shapes based on progress
     */
    private Path morphShapes(int fromShape, int toShape, float progress, float shapeSize) {
        Path path = new Path();

        // Get points for both shapes
        float[][] fromPoints = getShapePoints(fromShape, shapeSize);
        float[][] toPoints = getShapePoints(toShape, shapeSize);

        // Interpolate between points
        int maxPoints = Math.max(fromPoints.length, toPoints.length);
        float[][] morphedPoints = new float[maxPoints][2];

        for (int i = 0; i < maxPoints; i++) {
            int fromIndex = i % fromPoints.length;
            int toIndex = i % toPoints.length;

            morphedPoints[i][0] = lerp(fromPoints[fromIndex][0], toPoints[toIndex][0], progress);
            morphedPoints[i][1] = lerp(fromPoints[fromIndex][1], toPoints[toIndex][1], progress);
        }

        // Build path from morphed points
        if (morphedPoints.length > 0) {
            path.moveTo(centerX + morphedPoints[0][0], centerY + morphedPoints[0][1]);
            for (int i = 1; i < morphedPoints.length; i++) {
                path.lineTo(centerX + morphedPoints[i][0], centerY + morphedPoints[i][1]);
            }
            path.close();
        }

        return path;
    }

    /**
     * Get points for a specific shape
     */
    private float[][] getShapePoints(int shapeType, float shapeSize) {
        switch (shapeType) {
            case SHAPE_CIRCLE:
                return getCirclePoints(shapeSize, 32); // Circle approximated with 32 points
            case SHAPE_SQUARE:
                return getSquarePoints(shapeSize);
            case SHAPE_TRIANGLE:
                return getTrianglePoints(shapeSize);
            case SHAPE_PENTAGON:
                return getPentagonPoints(shapeSize);
            case SHAPE_HEXAGON:
                return getHexagonPoints(shapeSize);
            case SHAPE_STAR:
                return getStarPoints(shapeSize);
            default:
                return getCirclePoints(shapeSize, 32);
        }
    }

    private float[][] getCirclePoints(float radius, int numPoints) {
        float[][] points = new float[numPoints][2];
        for (int i = 0; i < numPoints; i++) {
            double angle = 2 * Math.PI * i / numPoints;
            points[i][0] = (float) (radius * Math.cos(angle));
            points[i][1] = (float) (radius * Math.sin(angle));
        }
        return points;
    }

    private float[][] getSquarePoints(float size) {
        float half = size / 2f;
        return new float[][]{
                {half, -half},
                {half, half},
                {-half, half},
                {-half, -half}
        };
    }

    private float[][] getTrianglePoints(float size) {
        float height = (float) (size * Math.sqrt(3) / 2);
        return new float[][]{
                {0, -size * 0.6f},
                {size * 0.866f, size * 0.3f},
                {-size * 0.866f, size * 0.3f}
        };
    }

    private float[][] getPentagonPoints(float radius) {
        float[][] points = new float[5][2];
        for (int i = 0; i < 5; i++) {
            double angle = 2 * Math.PI * i / 5 - Math.PI / 2;
            points[i][0] = (float) (radius * Math.cos(angle));
            points[i][1] = (float) (radius * Math.sin(angle));
        }
        return points;
    }

    private float[][] getHexagonPoints(float radius) {
        float[][] points = new float[6][2];
        for (int i = 0; i < 6; i++) {
            double angle = 2 * Math.PI * i / 6;
            points[i][0] = (float) (radius * Math.cos(angle));
            points[i][1] = (float) (radius * Math.sin(angle));
        }
        return points;
    }

    private float[][] getStarPoints(float outerRadius) {
        float innerRadius = outerRadius * 0.4f;
        float[][] points = new float[10][2];
        for (int i = 0; i < 10; i++) {
            double angle = 2 * Math.PI * i / 10 - Math.PI / 2;
            float radius = (i % 2 == 0) ? outerRadius : innerRadius;
            points[i][0] = (float) (radius * Math.cos(angle));
            points[i][1] = (float) (radius * Math.sin(angle));
        }
        return points;
    }

    /**
     * Linear interpolation
     */
    private float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int desiredSize = (int) (size * 1.5f + 2 * density);

        int width = resolveSize(desiredSize, widthMeasureSpec);
        int height = resolveSize(desiredSize, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    /**
     * Set custom colors
     */
    public void setColors(int outerColor, int innerColor) {
        shapePaint.setColor(outerColor);
        innerShapePaint.setColor(innerColor);
        invalidate();
    }

    /**
     * Set morph animation duration
     */
    public void setMorphDuration(long durationMs) {
        if (morphAnimator != null) {
            boolean wasRunning = morphAnimator.isRunning();
            morphAnimator.cancel();
            morphAnimator.setDuration(durationMs);
            if (wasRunning) {
                morphAnimator.start();
            }
        }
    }
}