package com.infinity.wallpaper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;

@SuppressLint({"SetTextI18n", "UnprotectedBroadcastReceiver"})
public class DownloadProgressActivity extends Activity {

    public static final String ACTION_FINISH = "com.infinity.wallpaper.ACTION_DOWNLOAD_FINISHED";
    public static final String ACTION_PROGRESS = "com.infinity.wallpaper.ACTION_DOWNLOAD_PROGRESS";
    public static final String EXTRA_PROGRESS = "extra_progress"; // int 0..100

    private TextView textView;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_PROGRESS.equals(action)) {
                int p = intent.getIntExtra(EXTRA_PROGRESS, -1);
                if (p >= 0) {
                    textView.setText("Downloading... " + p + "%");
                }
            } else if (ACTION_FINISH.equals(action)) {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zigzag_progress);
        setFinishOnTouchOutside(false);
        textView = findViewById(R.id.zigzag_text);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PROGRESS);
        f.addAction(ACTION_FINISH);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // On Android 14+ explicitly mark receiver as not exported to avoid SecurityException
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
        } catch (Exception e) {
            // fallback to safe registration
            try {
                registerReceiver(receiver, f);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
            finish();
        }
    }
}
