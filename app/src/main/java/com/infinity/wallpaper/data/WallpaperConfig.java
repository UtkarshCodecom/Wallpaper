package com.infinity.wallpaper.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;

public class WallpaperConfig {
    private static final String PREF = "wallpaper_prefs";
    private static final String KEY_BG = "bgUrl";
    private static final String KEY_MASK = "maskUrl";
    private static final String KEY_THEME1 = "theme1";

    public static void save(Context ctx, WallpaperItem item) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        e.putString(KEY_BG, item.bgUrl);
        e.putString(KEY_MASK, item.maskUrl);
        try {
            if (item.themes != null && item.themes.containsKey("theme1")) {
                Object t1 = item.themes.get("theme1");
                JSONObject jo = new JSONObject((Map) t1);
                e.putString(KEY_THEME1, jo.toString());
            } else {
                e.putString(KEY_THEME1, null);
            }
        } catch (Exception ex) {
            e.putString(KEY_THEME1, null);
        }
        // use commit to ensure config is persisted immediately before launching wallpaper chooser
        e.commit();
    }

    public static String getBgUrl(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_BG, null);
    }

    public static String getMaskUrl(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MASK, null);
    }

    public static String getTheme1Json(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_THEME1, null);
    }
}
