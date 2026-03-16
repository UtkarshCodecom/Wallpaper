package com.infinity.wallpaper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY = 1500L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView subtitle = findViewById(R.id.splash_subtitle);

        // Start the animated vector drawable pulsing
        Drawable d = logo.getDrawable();
        if (d instanceof Animatable) {
            ((Animatable) d).start();
        }

        // Logo scale-up entrance
        logo.setScaleX(0.5f);
        logo.setScaleY(0.5f);
        logo.setAlpha(0f);
        logo.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // Title fade in after a short delay
        title.animate()
                .alpha(1f)
                .setStartDelay(300)
                .setDuration(400)
                .start();

        // Subtitle fade in
        subtitle.animate()
                .alpha(1f)
                .setStartDelay(450)
                .setDuration(400)
                .start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent i = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_DELAY);
    }
}
