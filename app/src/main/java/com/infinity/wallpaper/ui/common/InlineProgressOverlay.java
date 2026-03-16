package com.infinity.wallpaper.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * Small in-place modal overlay used to show a single circular loading indicator.
 * Kept as a Dialog so it can be shown from Fragments without adding extra layouts.
 */
public final class InlineProgressOverlay {

    private InlineProgressOverlay() {}

    public static Dialog show(@NonNull Context context) {
        Dialog d = new Dialog(context);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(0x88000000); // translucent black scrim

        CircularProgressIndicator indicator = new CircularProgressIndicator(context);
        indicator.setIndeterminate(true);
        indicator.setIndicatorColor(Color.parseColor("#D84040")); // accent red
        indicator.setTrackColor(Color.parseColor("#331D1616"));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
        indicator.setLayoutParams(lp);
        root.addView(indicator);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        d.show();
        return d;
    }
}
