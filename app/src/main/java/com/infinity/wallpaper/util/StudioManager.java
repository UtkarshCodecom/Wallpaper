package com.infinity.wallpaper.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Stores the user's live studio edits as an override JSON layer on top of the base theme.
 * All edits are persisted immediately and the wallpaper service is notified to redraw.
 */
public final class StudioManager {

    private StudioManager() {}

    private static final String PREFS         = "wallpaper_prefs";
    private static final String KEY_OVERRIDES = "studio_overrides";

    // ── Read raw override JSON ─────────────────────────────────────────────

    public static String getOverridesJson(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OVERRIDES, "{}");
    }

    // ── Merge override onto a base theme JSON ─────────────────────────────

    /**
     * Deep-merge studio overrides into the base theme1 JSON.
     * Override keys win; base keys are kept when not overridden.
     */
    public static String mergeWithBase(String baseThemeJson, String overridesJson) {
        try {
            JSONObject base      = (baseThemeJson != null && !baseThemeJson.isEmpty())
                    ? new JSONObject(baseThemeJson) : new JSONObject();
            JSONObject overrides = (overridesJson != null && !overridesJson.isEmpty())
                    ? new JSONObject(overridesJson) : new JSONObject();

            // Deep-merge "time" sub-object
            if (overrides.has("time")) {
                JSONObject baseTime = base.optJSONObject("time");
                if (baseTime == null) baseTime = new JSONObject();
                JSONObject ovTime   = overrides.getJSONObject("time");
                java.util.Iterator<String> it = ovTime.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    baseTime.put(k, ovTime.get(k));
                }
                base.put("time", baseTime);
            }

            // Deep-merge "date" sub-object
            if (overrides.has("date")) {
                JSONObject baseDate = base.optJSONObject("date");
                if (baseDate == null) baseDate = new JSONObject();
                JSONObject ovDate   = overrides.getJSONObject("date");
                java.util.Iterator<String> it = ovDate.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    baseDate.put(k, ovDate.get(k));
                }
                base.put("date", baseDate);
            }

            return base.toString();
        } catch (Exception e) {
            return baseThemeJson != null ? baseThemeJson : "{}";
        }
    }

    // ── Write individual overrides ─────────────────────────────────────────

    private static void putTime(Context ctx, String key, Object value) {
        putInGroup(ctx, "time", key, value);
    }

    private static void putDate(Context ctx, String key, Object value) {
        putInGroup(ctx, "date", key, value);
    }

    private static void putInGroup(Context ctx, String group, String key, Object value) {
        try {
            JSONObject ov = new JSONObject(getOverridesJson(ctx));
            JSONObject g  = ov.optJSONObject(group);
            if (g == null) g = new JSONObject();
            if (value == null) g.remove(key);
            else               g.put(key, value);
            ov.put(group, g);
            save(ctx, ov.toString());
        } catch (Exception ignored) {}
    }

    private static void save(Context ctx, String json) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_OVERRIDES, json).apply();
    }

    /** Clear ALL studio overrides (reset to base theme). */
    public static void clearAll(Context ctx) {
        save(ctx, "{}");
    }

    /** Clear just one key inside "time" group */
    public static void resetTimeKey(Context ctx, String key) {
        putTime(ctx, key, null);
    }

    /** Clear just one key inside "date" group */
    public static void resetDateKey(Context ctx, String key) {
        putDate(ctx, key, null);
    }

    // ── Time overrides ─────────────────────────────────────────────────────

    public static void setFontSize(Context ctx, float size)       { putTime(ctx, "size", size); }
    public static void setPosX(Context ctx, float x)              { putTime(ctx, "x", x); }
    public static void setPosY(Context ctx, float y)              { putTime(ctx, "y", y); }
    public static void setRotation(Context ctx, float rot)        { putTime(ctx, "rotation", rot); }
    public static void setFont(Context ctx, String font)          { putTime(ctx, "font", font); }
    public static void setDepthMode(Context ctx, String mode)     { putTime(ctx, "depthMode", mode); }
    public static void setConnectorBehindMask(Context ctx, boolean behind) { putTime(ctx, "connectorBehindMask", behind); }
    public static void setLetterSpacing(Context ctx, float ls)    { putTime(ctx, "letterSpacing", ls); }
    public static void setHourColor(Context ctx, String c)        { putTime(ctx, "hourColor", c); }
    public static void setMinuteColor(Context ctx, String c)      { putTime(ctx, "minuteColor", c); }
    public static void setOpacity(Context ctx, float op)          { putTime(ctx, "opacity", op); }
    public static void setShadowEnabled(Context ctx, boolean en)  { putTime(ctx, "shadowEnabled", en); }
    public static void setShadowX(Context ctx, float x)           { putTime(ctx, "shadowX", x); }
    public static void setShadowY(Context ctx, float y)           { putTime(ctx, "shadowY", y); }
    public static void setStrokeEnabled(Context ctx, boolean en)  { putTime(ctx, "strokeEnabled", en); }
    public static void setStrokeWidth(Context ctx, float w)       { putTime(ctx, "strokeWidth", w); }
    public static void setStrokeColor(Context ctx, String c)      { putTime(ctx, "strokeColor", c); }
    public static void setStrokeTarget(Context ctx, String t)     { putTime(ctx, "strokeTarget", t); }
    public static void setStretchX(Context ctx, float s)          { putTime(ctx, "stretchX", s); }
    public static void setStretchY(Context ctx, float s)          { putTime(ctx, "stretchY", s); }
    public static void setSkewH(Context ctx, float s)             { putTime(ctx, "skewH", s); }
    public static void setSkewV(Context ctx, float s)             { putTime(ctx, "skewV", s); }
    public static void setSkewBottomH(Context ctx, float s)       { putTime(ctx, "skewBottomH", s); }
    public static void setSkewLeftV(Context ctx, float s)         { putTime(ctx, "skewLeftV", s); }
    public static void setClockStyle(Context ctx, String style)   { putTime(ctx, "clockStyle", style); }

    // Gradient for time text
    public static void setTimeGradientEnabled(Context ctx, boolean en) { putTime(ctx, "timeGradientEnabled", en); }
    public static void setTimeGradientAngle(Context ctx, float angle)  { putTime(ctx, "timeGradientAngle", angle); }

    // Fill vs stroke
    public static void setFillEnabled(Context ctx, boolean en)    { putTime(ctx, "fillEnabled", en); }

    // Glow
    public static void setGlowRadius(Context ctx, float r)        { putTime(ctx, "glowRadius", r); }
    public static void setGlowColor(Context ctx, String c)        { putTime(ctx, "glowColor", c); }

    // Mask opacity (stored in time group as it affects the overall theme)
    public static void setMaskOpacity(Context ctx, float op)      { putTime(ctx, "maskOpacity", op); }

    // ── Date overrides ─────────────────────────────────────────────────────

    public static void setDateVisible(Context ctx, boolean v)     { putDate(ctx, "visible", v); }
    public static void setDateFontSize(Context ctx, float s)      { putDate(ctx, "size", s); }
    public static void setDatePosX(Context ctx, float x)          { putDate(ctx, "x", x); }
    public static void setDatePosY(Context ctx, float y)          { putDate(ctx, "y", y); }
    public static void setDateRotation(Context ctx, float r)      { putDate(ctx, "rotation", r); }
    public static void setDateFont(Context ctx, String f)         { putDate(ctx, "font", f); }
    public static void setDateColor(Context ctx, String c)        { putDate(ctx, "color", c); }
    public static void setDateFormat(Context ctx, String fmt)     { putDate(ctx, "format", fmt); }
    public static void setDateAllCaps(Context ctx, boolean v)     { putDate(ctx, "allCaps", v); }

    // ── Getters for UI restore ─────────────────────────────────────────────

    public static JSONObject getTimeOverrides(Context ctx) {
        try {
            JSONObject ov = new JSONObject(getOverridesJson(ctx));
            JSONObject t  = ov.optJSONObject("time");
            return t != null ? t : new JSONObject();
        } catch (Exception e) { return new JSONObject(); }
    }

    public static JSONObject getDateOverrides(Context ctx) {
        try {
            JSONObject ov = new JSONObject(getOverridesJson(ctx));
            JSONObject d  = ov.optJSONObject("date");
            return d != null ? d : new JSONObject();
        } catch (Exception e) { return new JSONObject(); }
    }

    /** Returns the merged theme JSON that should be fed to the renderer. */
    public static String getEffectiveThemeJson(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String base      = p.getString("theme_json", "");
        String overrides = p.getString(KEY_OVERRIDES, "{}");
        return mergeWithBase(base, overrides);
    }

    // Curvature (arc text)
    public static void setCurvature(Context ctx, float curvature)  { putTime(ctx, "curvature", curvature); }
}
