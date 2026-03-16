package com.infinity.wallpaper.util;

import android.content.Context;

public class SettingsManager {
    public static final String ACTION_SETTINGS_CHANGED = "com.infinity.wallpaper.ACTION_SETTINGS_CHANGED";
    public static final String ACTION_ASSETS_READY = "com.infinity.wallpaper.ACTION_ASSETS_READY";

    private static final String PREFS = "wallpaper_prefs";

    private static final String KEY_LOCK_CLOCK          = "lock_clock";
    private static final String KEY_HOME_CLOCK          = "home_clock";
    private static final String KEY_24H                 = "clock_24h";
    private static final String KEY_GYRO                = "gyro_enabled";
    private static final String KEY_MOTION_MODE         = "motion_mode";      // 0=tilt,1=shift
    private static final String KEY_MOTION_AMOUNT       = "motion_amount";    // 0-100
    private static final String KEY_MOTION_SENS         = "motion_sensitivity"; // 0-100
    private static final String KEY_CLOCK_ANIM_ENABLED  = "clock_anim_enabled";
    private static final String KEY_CLOCK_ANIM_STYLE    = "clock_anim_style"; // int index 0-7
    private static final String KEY_CLOCK_ANIM_SPEED    = "clock_anim_speed"; // 0-100 (0=slow,50=normal,100=fast)

    // ── Clock display ──────────────────────────────────────────────────────
    public static boolean isLockClockEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK_CLOCK, true);
    }
    public static void setLockClockEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOCK_CLOCK, v).apply();
    }

    public static boolean isHomeClockEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_CLOCK, true);
    }
    public static void setHomeClockEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_CLOCK, v).apply();
    }

    public static boolean is24Hour(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_24H, false);
    }
    public static void set24Hour(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_24H, v).apply();
    }

    // ── Gyroscope / motion ─────────────────────────────────────────────────
    public static boolean isGyroEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GYRO, true);
    }
    public static void setGyroEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO, v).apply();
    }

    public static int getMotionMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MOTION_MODE, 0);
    }
    public static void setMotionMode(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MOTION_MODE, mode).apply();
    }

    /** Motion amount (0-100): how far the clock travels with tilt/shift. */
    public static int getMotionAmount(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MOTION_AMOUNT, 50);
    }
    public static void setMotionAmount(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MOTION_AMOUNT, v).apply();
    }

    /** Motion sensitivity (0-100): how responsive the gyro feels. */
    public static int getMotionSensitivity(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MOTION_SENS, 50);
    }
    public static void setMotionSensitivity(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MOTION_SENS, v).apply();
    }

    // ── Clock animation ────────────────────────────────────────────────────
    public static boolean isClockAnimationEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLOCK_ANIM_ENABLED, true);
    }
    public static void setClockAnimationEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOCK_ANIM_ENABLED, v).apply();
    }

    /**
     * Style index 0-7:
     * 0=FadeScale, 1=SlideUp, 2=SlideDown, 3=SlideLeft, 4=SlideRight,
     * 5=StretchVert, 6=StretchVertLn, 7=ParallaxDrift
     */
    public static int getClockAnimationStyle(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLOCK_ANIM_STYLE, 0);
    }
    public static void setClockAnimationStyle(Context ctx, int style) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLOCK_ANIM_STYLE, style).apply();
    }

    /**
     * Animation speed (0-100). 0 = very slow (1200ms), 50 = normal (600ms), 100 = fast (200ms).
     */
    public static int getClockAnimationSpeed(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLOCK_ANIM_SPEED, 50);
    }
    public static void setClockAnimationSpeed(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLOCK_ANIM_SPEED, v).apply();
    }

    /**
     * Convert speed slider (0-100) to animation duration in ms.
     * 0→1200ms, 50→600ms, 100→200ms (exponential curve feels natural).
     */
    public static long getAnimDurationMs(Context ctx) {
        int speed = getClockAnimationSpeed(ctx); // 0-100
        // Interpolate: slow(0)=1200, mid(50)=600, fast(100)=200
        float t = speed / 100f;
        long duration = Math.round(1200f - t * t * 1000f); // quadratic ease
        return Math.max(200, Math.min(1200, duration));
    }
}
