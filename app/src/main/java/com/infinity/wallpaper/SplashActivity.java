package com.infinity.wallpaper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.Nullable;

public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY = 1000L; // milliseconds

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        Drawable d = logo.getDrawable();
        if (d instanceof Animatable) {
            ((Animatable) d).start();
        }

        // After the animation runs a bit, start MainActivity which hosts the bottom navigation
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent i = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(i);
            finish();
        }, SPLASH_DELAY);
    }
}
