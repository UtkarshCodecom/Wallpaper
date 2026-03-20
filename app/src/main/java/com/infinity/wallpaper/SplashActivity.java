package com.infinity.wallpaper;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public class SplashActivity extends Activity {
    private static final long RED_HOLD_MS = 300L;
    private static final long BURST_DURATION_MS = 700L;
    private static final long FADE_OUT_MS = 300L;
    private static final long SAFETY_MARGIN_MS = 100L;
    private static final long SPLASH_TOTAL_MS = RED_HOLD_MS + BURST_DURATION_MS + FADE_OUT_MS + SAFETY_MARGIN_MS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean launched = new AtomicBoolean(false);
    private volatile Runnable fallbackLaunch = this::launchMain;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View root = findViewById(R.id.splash_root);
        ImageView burst = findViewById(R.id.splash_firework);

        if (burst != null) {
            burst.setAlpha(0f);
            burst.setScaleX(0f);
            burst.setScaleY(0f);
        }

        startFireworkAnimation(root, burst);
    }

    private void startFireworkAnimation(View root, ImageView burst) {
        if (root == null || burst == null) {
            handler.postDelayed(fallbackLaunch, SPLASH_TOTAL_MS);
            return;
        }

        int accent = ContextCompat.getColor(this, R.color.accent);
        int black = ContextCompat.getColor(this, R.color.black);

        ValueAnimator backgroundFade = ValueAnimator.ofArgb(accent, black);
        backgroundFade.setStartDelay(RED_HOLD_MS);
        backgroundFade.setDuration(BURST_DURATION_MS + FADE_OUT_MS);
        backgroundFade.setInterpolator(new DecelerateInterpolator());
        backgroundFade.addUpdateListener(anim -> root.setBackgroundColor((int) anim.getAnimatedValue()));

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(burst, View.SCALE_X, 0f, 1.35f);
        scaleX.setStartDelay(RED_HOLD_MS);
        scaleX.setDuration(BURST_DURATION_MS);
        scaleX.setInterpolator(new OvershootInterpolator(1.2f));

        ObjectAnimator scaleY = ObjectAnimator.ofFloat(burst, View.SCALE_Y, 0f, 1.35f);
        scaleY.setStartDelay(RED_HOLD_MS);
        scaleY.setDuration(BURST_DURATION_MS);
        scaleY.setInterpolator(new OvershootInterpolator(1.2f));

        ObjectAnimator alpha = ObjectAnimator.ofFloat(burst, View.ALPHA, 0f, 1f, 0f);
        alpha.setStartDelay(RED_HOLD_MS);
        alpha.setDuration(BURST_DURATION_MS + FADE_OUT_MS);
        alpha.setInterpolator(new AccelerateDecelerateInterpolator());

        handler.postDelayed(fallbackLaunch, SPLASH_TOTAL_MS);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(backgroundFade, scaleX, scaleY, alpha);
        // Ensure MainActivity launches when the animation finishes or is canceled; fallback runnable is kept so it can be canceled cleanly
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
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
