package com.infinity.wallpaper.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Custom layout manager for carousel effect where:
 * - Center items are full width
 * - Edge items shrink in width and scale smoothly
 * - Creates a "dock-like" expanding effect when scrolling
 */
public class CarouselLayoutManager extends LinearLayoutManager {

    private final float minScale = 0.85f;
    private final float maxScale = 1.0f;
    private final float minAlpha = 0.7f;
    private final float maxAlpha = 1.0f;

    public CarouselLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        applyCarouselEffect();
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int scrolled = super.scrollHorizontallyBy(dx, recycler, state);
        applyCarouselEffect();
        return scrolled;
    }

    private void applyCarouselEffect() {
        int childCount = getChildCount();
        if (childCount == 0) return;

        int parentWidth = getWidth();
        int parentCenter = parentWidth / 2;

        // Distance at which item reaches minimum scale
        float maxDistance = parentWidth * 0.4f;

        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child == null) continue;

            int childCenter = (child.getLeft() + child.getRight()) / 2;
            float distanceFromCenter = Math.abs(parentCenter - childCenter);

            // Calculate factor: 0 at center, 1 at maxDistance
            float factor = Math.min(1f, distanceFromCenter / maxDistance);

            // Apply smooth interpolation
            float scale = lerp(maxScale, minScale, factor);
            float alpha = lerp(maxAlpha, minAlpha, factor);

            // Apply transforms
            child.setPivotX(child.getWidth() / 2f);
            child.setPivotY(child.getHeight() / 2f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);

            // Apply slight elevation change for depth
            child.setTranslationZ(lerp(8f, 0f, factor));
        }
    }

    private float lerp(float start, float end, float factor) {
        return start + (end - start) * factor;
    }
}
