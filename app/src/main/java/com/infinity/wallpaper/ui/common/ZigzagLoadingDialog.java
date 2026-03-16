package com.infinity.wallpaper.ui.common;

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

import com.infinity.wallpaper.R;

/**
 * Reuses the existing zigzag loading layout but as a dialog overlay (no new Activity).
 */
public final class ZigzagLoadingDialog {

    private ZigzagLoadingDialog() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static Dialog show(@NonNull Context context, @Nullable String message) {
        Dialog d = new Dialog(context);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        View v = LayoutInflater.from(context).inflate(R.layout.activity_zigzag_progress, new android.widget.FrameLayout(context), false);
        TextView tv = v.findViewById(R.id.zigzag_text);
        if (tv != null) tv.setText(message != null ? message : "Loading...");

        d.setContentView(v);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        d.show();
        return d;
    }

    /** Update the message text of a running dialog safely from any thread. */
    public static void updateMessage(@Nullable Dialog dialog, @Nullable String message) {
        if (dialog == null || !dialog.isShowing()) return;
        UI.post(() -> {
            if (!dialog.isShowing()) return;
            TextView tv = dialog.findViewById(R.id.zigzag_text);
            if (tv != null && message != null) tv.setText(message);
        });
    }

    /** Dismiss safely from any thread. */
    public static void dismiss(@Nullable Dialog dialog) {
        if (dialog == null) return;
        UI.post(() -> {
            try { dialog.dismiss(); } catch (Exception ignored) {}
        });
    }
}
