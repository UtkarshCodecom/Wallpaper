package com.walle.wallpaper.ui.common;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * SwipeRefreshLayout that exposes pull distance in real time.
 * This is more reliable than trying to infer pull via translationY.
 */
public class PullAwareSwipeRefreshLayout extends SwipeRefreshLayout {

    private OnPullListener onPullListener;
    // tuned to feel like stock SwipeRefreshLayout threshold
    private float triggerDistancePx;

    public PullAwareSwipeRefreshLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public PullAwareSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        triggerDistancePx = 120f * d;
    }

    public void setOnPullListener(@Nullable OnPullListener l) {
        onPullListener = l;
    }

    public float getTriggerDistancePx() {
        return triggerDistancePx;
    }

    public void setTriggerDistancePx(float px) {
        triggerDistancePx = Math.max(1f, px);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull android.view.View child, @NonNull android.view.View target, int axes, int type) {
        return super.onStartNestedScroll(child, target, axes, type);
    }

    @Override
    public void onNestedPreScroll(@NonNull android.view.View target, int dx, int dy, @NonNull int[] consumed, int type) {
        // dy>0 means user scrolling up (content goes up) - reduce pull distance
        super.onNestedPreScroll(target, dx, dy, consumed, type);
        dispatchPull(target);
    }

    @Override
    public void onNestedScroll(@NonNull android.view.View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type);
        dispatchPull(target);
    }

    @Override
    public void onStopNestedScroll(@NonNull android.view.View target, int type) {
        super.onStopNestedScroll(target, type);
        dispatchPull(target);
    }

    private void dispatchPull(@NonNull android.view.View target) {
        if (onPullListener == null) return;
        // When pulling down, SwipeRefreshLayout translates its direct child.
        android.view.View directChild = getChildCount() > 0 ? getChildAt(0) : null;
        float pull = 0f;
        if (directChild != null) {
            pull = Math.max(0f, directChild.getTranslationY());
        }
        onPullListener.onPull(pull, triggerDistancePx);
    }

    public interface OnPullListener {
        /**
         * @param pullDistancePx current pull distance (>= 0)
         * @param thresholdPx    distance needed to trigger refresh
         */
        void onPull(float pullDistancePx, float thresholdPx);
    }
}
