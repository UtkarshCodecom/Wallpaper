package com.infinity.wallpaper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

public class SplashActivity extends Activity {
    private static final long BURST_MS = 1250L;
    private static final long FADE_TO_BLACK_MS = 380L;
    private static final long SAFETY_MARGIN_MS = 250L;
    private static final long SPLASH_TOTAL_MS = BURST_MS + FADE_TO_BLACK_MS + SAFETY_MARGIN_MS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean launched = new AtomicBoolean(false);
    private volatile Runnable fallbackLaunch = this::launchMain;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View blackOverlay = findViewById(R.id.splash_black_overlay);
        if (blackOverlay != null) {
            blackOverlay.setAlpha(0f);
        }

        startBurstSequence(blackOverlay);
    }

    private void startBurstSequence(View blackOverlay) {
        handler.postDelayed(fallbackLaunch, SPLASH_TOTAL_MS);

        if (blackOverlay == null) {
            return;
        }

        // Sync a guaranteed final blackout with the view's internal black spread.
        ObjectAnimator blackIn = ObjectAnimator.ofFloat(blackOverlay, View.ALPHA, 0f, 1f);
        blackIn.setStartDelay(BURST_MS);
        blackIn.setDuration(FADE_TO_BLACK_MS);
        blackIn.setInterpolator(new DecelerateInterpolator());

        AnimatorSet set = new AnimatorSet();
        set.playTogether(blackIn);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                launchMain();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                launchMain();
            }
        });
        set.start();
    }

    private void launchMain() {
        if (!launched.compareAndSet(false, true)) {
            return;
        }
        if (fallbackLaunch != null) {
            handler.removeCallbacks(fallbackLaunch);
        }
        Intent i = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(i);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
