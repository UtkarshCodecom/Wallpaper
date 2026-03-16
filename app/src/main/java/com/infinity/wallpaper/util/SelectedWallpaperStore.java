package com.infinity.wallpaper.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.infinity.wallpaper.data.WallpaperItem;

/**
 * Persists the user's currently selected wallpaper globally (across screens and app restarts).
 */
public final class SelectedWallpaperStore {

    private SelectedWallpaperStore() {}

    private static final String PREFS = "wallpaper_prefs";
    private static final String KEY_SELECTED_ID = "selected_wallpaper_id";

    public static void setSelectedId(@NonNull Context ctx, @Nullable String id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_ID, id != null ? id : "")
                .apply();
    }

    @Nullable
    public static String getSelectedId(@NonNull Context ctx) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_ID, "");
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static void setSelected(@NonNull Context ctx, @Nullable WallpaperItem item) {
        setSelectedId(ctx, item != null ? item.id : null);
    }
}
