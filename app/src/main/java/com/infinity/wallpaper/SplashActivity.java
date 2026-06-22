package com.infinity.wallpaper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.infinity.wallpaper.ui.widgets.SplashLogoView;

import java.util.concurrent.atomic.AtomicBoolean;

public class SplashActivity extends Activity {

    private static final long SAFETY_TIMEOUT_MS = 3200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean launched = new AtomicBoolean(false);
    private Runnable fallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        fallback = this::launchMain;
        handler.postDelayed(fallback, SAFETY_TIMEOUT_MS);

        SplashLogoView logo = findViewById(R.id.splash_logo);
        if (logo != null) logo.setOnAnimationEndListener(this::launchMain);
    }

    private void launchMain() {
        if (!launched.compareAndSet(false, true)) return;
        handler.removeCallbacks(fallback);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}