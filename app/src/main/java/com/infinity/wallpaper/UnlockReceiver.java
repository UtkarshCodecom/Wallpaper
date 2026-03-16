package com.infinity.wallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Static receiver for ACTION_USER_PRESENT (device unlocked).
 * Immediately tells the wallpaper service to redraw so the home-screen clock
 * appears instantly without waiting for the next minute tick.
 */
public class UnlockReceiver extends BroadcastReceiver {

    private static final String TAG = "UnlockReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onReceive: " + action);

        if (Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {

            // Notify the wallpaper service to redraw immediately
            Intent notify = new Intent(MyWallpaperServiceNew.ACTION_REDRAW);
            notify.setPackage(context.getPackageName());
            context.sendBroadcast(notify);
            Log.d(TAG, "Sent ACTION_REDRAW to wallpaper service");
        }
    }
}
