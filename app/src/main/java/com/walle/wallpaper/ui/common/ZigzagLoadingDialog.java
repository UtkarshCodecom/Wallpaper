package com.walle.wallpaper.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.walle.wallpaper.R;

/**
 * Reuses the existing zigzag loading layout but as a dialog overlay (no new Activity).
 */
public final class ZigzagLoadingDialog {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ZigzagLoadingDialog() {
    }

    public static Dialog show(@NonNull Context context, @Nullable String message) {
        Dialog d = new Dialog(context);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        View v = LayoutInflater.from(context).inflate(R.layout.activity_zigzag_progress, new android.widget.FrameLayout(context), false);
        TextView tv = v.findViewById(R.id.zigzag_text);
        if (tv != null) tv.setText(message != null ? message : "Loading...");

        d.setContentView(v);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Glassmorphism: dim + frosted blur behind the translucent card.
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();
                    lp.setBlurBehindRadius(50);
                    w.setAttributes(lp);
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                } catch (Throwable ignored) {
                }
            }
        }
        d.show();
        return d;
    }

    /**
     * Update the message text of a running dialog safely from any thread.
     */
    public static void updateMessage(@Nullable Dialog dialog, @Nullable String message) {
        if (dialog == null || !dialog.isShowing()) return;
        UI.post(() -> {
            if (!dialog.isShowing()) return;
            TextView tv = dialog.findViewById(R.id.zigzag_text);
            if (tv != null && message != null) tv.setText(message);
        });
    }

    /**
     * Drive the 12-gon progress ring (0..100). Safe from any thread; ignores unknown (&lt;0).
     */
    public static void updateProgress(@Nullable Dialog dialog, int percent) {
        if (dialog == null || percent < 0) return;
        UI.post(() -> {
            if (!dialog.isShowing()) return;
            com.walle.wallpaper.ui.widgets.PolygonProgressView ring = dialog.findViewById(R.id.progress_polygon);
            if (ring != null) ring.setProgress(percent / 100f);
        });
    }

    /**
     * Dismiss safely from any thread.
     */
    public static void dismiss(@Nullable Dialog dialog) {
        if (dialog == null) return;
        UI.post(() -> {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        });
    }
}
