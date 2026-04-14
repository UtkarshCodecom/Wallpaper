package com.infinity.wallpaper.ui.common;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Shows a custom refresh overlay while the user pulls down, then starts the pulse animation
 * only when the refresh is actually triggered (release past threshold).
 */
public final class PullRevealRefreshController {

    private final PullAwareSwipeRefreshLayout swipe;
    private final View container;
    private final PulseRefreshView pulse;

    private final float revealDistancePx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean refreshing = false;
    private final Runnable finishRunnable = this::doFinishNow;
    // Keep the refreshing UI visible long enough to be perceived.
    private long minVisibleMs = 2500L;
    private long refreshStartUptimeMs = 0L;

    public PullRevealRefreshController(
            @NonNull PullAwareSwipeRefreshLayout swipe,
            @NonNull View container,
            @NonNull PulseRefreshView pulse
    ) {
        this.swipe = swipe;
        this.container = container;
        this.pulse = pulse;

        float density = swipe.getResources().getDisplayMetrics().density;
        this.revealDistancePx = 120f * density;

        container.setVisibility(View.GONE);
        container.setAlpha(0f);
        container.setTranslationY(-9999f);
        pulse.stopAnimation();

        swipe.setOnPullListener((pullDistancePx, thresholdPx) -> {
            if (refreshing) return;
            updateRevealByPull(pullDistancePx);
        });
    }

    /**
     * Minimum time to keep the refreshing animation visible once refresh triggers.
     */
    public void setMinVisibleMs(long ms) {
        minVisibleMs = Math.max(0L, ms);
    }

    /**
     * Use this instead of swipe.setOnRefreshListener directly.
     */
    public void setOnRefresh(@NonNull Runnable onRefresh) {
        swipe.setOnRefreshListener(() -> {
            refreshing = true;
            refreshStartUptimeMs = SystemClock.uptimeMillis();
            mainHandler.removeCallbacks(finishRunnable);

            container.setVisibility(View.VISIBLE);
            container.setAlpha(1f);
            container.setTranslationY(0f);
            pulse.setPullProgress(1f);
            pulse.startAnimation();
            onRefresh.run();
        });
    }

    /**
     * Call when your refresh finishes (success or failure).
     */
    public void finish() {
        if (!refreshing) {
            // Still ensure we hide in case somebody calls finish() after a config change.
            doFinishNow();
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - refreshStartUptimeMs;
        long remain = Math.max(0L, minVisibleMs - elapsed);

        mainHandler.removeCallbacks(finishRunnable);
        if (remain <= 0L) {
            doFinishNow();
        } else {
            mainHandler.postDelayed(finishRunnable, remain);
        }
    }

    /**
     * Run a task after the refresh animation has been visible for at least {@link #minVisibleMs}.
     * Useful when you want the UI/data update to happen AFTER the animation, not during it.
     */
    public void runAfterMinVisible(@NonNull Runnable action) {
        if (!refreshing) {
            mainHandler.post(action);
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - refreshStartUptimeMs;
        long remain = Math.max(0L, minVisibleMs - elapsed);
        mainHandler.postDelayed(action, remain);
    }

    private void doFinishNow() {
        refreshing = false;
        swipe.setRefreshing(false);
        pulse.stopAnimation();
        pulse.setPullProgress(0f);

        container.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            container.setVisibility(View.GONE);
            container.setTranslationY(-container.getHeight());
        }).start();
    }

    private void updateRevealByPull(float pullPx) {
        float t = Math.min(1f, Math.max(0f, pullPx / revealDistancePx));

        if (t > 0f) {
            container.setVisibility(View.VISIBLE);
            // Ensure we have a measured height
            int h = container.getHeight();
            if (h <= 0) {
                container.measure(
                        View.MeasureSpec.makeMeasureSpec(swipe.getWidth(), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                );
                h = container.getMeasuredHeight();
            }
            container.setTranslationY(-h * (1f - t));
            container.setAlpha(t);

            // Pre-release feedback: don't start the full morphing anim until refresh triggers.
            pulse.stopAnimation();
            pulse.setPullProgress(t);
        } else {
            pulse.setPullProgress(0f);
            container.setAlpha(0f);
            container.setTranslationY(-container.getHeight());
            container.setVisibility(View.GONE);
        }
    }
}
