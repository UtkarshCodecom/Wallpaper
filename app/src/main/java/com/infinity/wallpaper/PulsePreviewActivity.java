package com.infinity.wallpaper;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.infinity.wallpaper.ui.common.PulseRefreshView;

/**
 * TEMP screen to preview PulseRefreshView sizes/behavior.
 * Do not ship to production.
 */
public class PulsePreviewActivity extends AppCompatActivity {

    private PulseRefreshView big;
    private PulseRefreshView normal;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pulse_preview);

        big = findViewById(R.id.pulse_refresh_big);
        normal = findViewById(R.id.pulse_refresh_normal);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (big != null) big.startAnimation();
        if (normal != null) normal.startAnimation();
    }

    @Override
    protected void onStop() {
        if (big != null) big.stopAnimation();
        if (normal != null) normal.stopAnimation();
        super.onStop();
    }
}
