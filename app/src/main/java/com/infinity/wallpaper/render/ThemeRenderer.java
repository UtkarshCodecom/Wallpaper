package com.infinity.wallpaper.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import com.infinity.wallpaper.util.SettingsManager;

import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ThemeRenderer {

    // Animation style constants
    public static final int ANIM_FADE_SCALE       = 0;
    public static final int ANIM_SLIDE_UP         = 1;
    public static final int ANIM_SLIDE_DOWN       = 2;
    public static final int ANIM_SLIDE_LEFT       = 3;
    public static final int ANIM_SLIDE_RIGHT      = 4;
    public static final int ANIM_STRETCH_VERT     = 5;
    public static final int ANIM_STRETCH_VERT_LN  = 6;
    public static final int ANIM_PARALLAX_DRIFT   = 7;

    private final Context context;
    private final Map<String, Typeface> fontCache = new HashMap<>();

    public ThemeRenderer(Context ctx) {
        this.context = ctx;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public Bitmap renderThemeBitmap(String theme1Json) {
        return renderFull(theme1Json, 1080, 1920, true, 0, 0, 0, 0, -1, 1f, ANIM_FADE_SCALE);
    }

    public Bitmap renderThemeBitmap(String theme1Json, int w, int h) {
        return renderFull(theme1Json, w, h, true, 0, 0, 0, 0, -1, 1f, ANIM_FADE_SCALE);
    }

    public Bitmap renderThemeBitmap(String theme1Json, int w, int h, boolean showTime, float offX, float offY) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, 0, 0, -1, 1f, ANIM_FADE_SCALE);
    }

    /** Gyro-only overload (no clock animation) */
    public Bitmap renderThemeBitmap(String theme1Json, int w, int h, boolean showTime,
                                     float offX, float offY, float pitch, float roll, int motionMode) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, pitch, roll, motionMode, 1f, ANIM_FADE_SCALE);
    }

    /** Full overload: gyro + clock animation */
    public Bitmap renderThemeBitmap(String theme1Json, int w, int h, boolean showTime,
                                     float offX, float offY, float pitch, float roll,
                                     int motionMode, float animPhase, int animStyle) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, pitch, roll, motionMode, animPhase, animStyle);
    }

    /**
     * Render only the BACK layer (element that goes behind the mask).
     * Used when depthMode != "none".
     */
    public Bitmap renderBackLayer(String theme1Json, int w, int h, boolean showTime,
                                   float offX, float offY, float pitch, float roll,
                                   int motionMode, float animPhase, int animStyle) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, pitch, roll, motionMode, animPhase, animStyle, "back");
    }

    /** Simple (no gyro) overload for back layer */
    public Bitmap renderBackLayer(String theme1Json, int w, int h, boolean showTime, float offX, float offY) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, 0, 0, -1, 1f, ANIM_FADE_SCALE, "back");
    }

    /**
     * Render only the FRONT layer (element that goes in front of the mask).
     * Returns null if depthMode == "none" (no split needed).
     */
    public Bitmap renderFrontLayer(String theme1Json, int w, int h, boolean showTime,
                                    float offX, float offY, float pitch, float roll,
                                    int motionMode, float animPhase, int animStyle) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, pitch, roll, motionMode, animPhase, animStyle, "front");
    }

    /** Simple (no gyro) overload for front layer */
    public Bitmap renderFrontLayer(String theme1Json, int w, int h, boolean showTime, float offX, float offY) {
        return renderFull(theme1Json, w, h, showTime, offX, offY, 0, 0, -1, 1f, ANIM_FADE_SCALE, "front");
    }

    /** Returns the depthMode from the effective theme JSON, or "none" */
    public static String getDepthMode(String theme1Json) {
        try {
            JSONObject t = new JSONObject(theme1Json);
            JSONObject time = t.optJSONObject("time");
            if (time == null) return "none";
            return time.optString("depthMode", "none");
        } catch (Exception e) { return "none"; }
    }

    // ── Core renderer ───────────────────────────────────────────────────────

    private Bitmap renderFull(String theme1Json, int bmpW, int bmpH, boolean showTime,
                               float offX, float offY, float pitch, float roll,
                               int motionMode, float animPhase, int animStyle) {
        return renderFull(theme1Json, bmpW, bmpH, showTime, offX, offY, pitch, roll, motionMode, animPhase, animStyle, "all");
    }

    /** layerPass: "all" = everything, "back" = element behind mask, "front" = element in front of mask */
    private Bitmap renderFull(String theme1Json, int bmpW, int bmpH, boolean showTime,
                               float offX, float offY, float pitch, float roll,
                               int motionMode, float animPhase, int animStyle, String layerPass) {
        try {
            if (theme1Json == null || theme1Json.isEmpty()) {
                if (!showTime || !"all".equals(layerPass)) return null;
                return createSimpleTimeBitmap(DateFormat.format(getTimeFormat(), new Date()).toString(), bmpW, bmpH);
            }

            JSONObject theme = new JSONObject(theme1Json);
            Bitmap result = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

            boolean drewSomething = false;

            JSONObject dateObj = null;
            boolean dateVisible = false;
            if (theme.has("date")) {
                dateObj = theme.getJSONObject("date");
                dateVisible = dateObj.optBoolean("visible", true);
            } else {
                dateObj = new JSONObject();
                dateVisible = true;
            }

            boolean gyroActive = (motionMode >= 0);

            if (showTime && theme.has("time")) {
                JSONObject timeObj = theme.getJSONObject("time");
                renderTimeFull(canvas, timeObj, dateObj, dateVisible, bmpW, bmpH,
                        offX, offY, pitch, roll, motionMode, gyroActive, animPhase, animStyle, layerPass);
                drewSomething = true;
            } else if (dateVisible && "all".equals(layerPass)) {
                renderDateWithMotion(canvas, dateObj, bmpW, bmpH,
                        offX, offY, pitch, roll, motionMode, gyroActive, animPhase, animStyle);
                drewSomething = true;
            }

            if (!drewSomething) { result.recycle(); return null; }
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            if (!showTime || !"all".equals(layerPass)) return null;
            return createSimpleTimeBitmap(DateFormat.format(getTimeFormat(), new Date()).toString(), bmpW, bmpH);
        }
    }

    // ── renderTimeFull ───────────────────────────────────────────────────────

    private void renderTimeFull(Canvas canvas, JSONObject timeObj, JSONObject dateObj, boolean dateVisible,
                                 int cW, int cH,
                                 float offX, float offY, float pitch, float roll, int motionMode,
                                 boolean gyroActive, float animPhase, int animStyle, String layerPass) {
        try {
            // ── Clock style & time parts ──
            String clockStyle = timeObj.optString("clockStyle", "HH:MM");
            boolean isSeconds  = "HH:MM:SS".equals(clockStyle) || "HH/MM/SS".equals(clockStyle);
            boolean isVertical = "VERTICAL".equals(clockStyle);
            boolean isVerticalSS = "VERTICAL_SS".equals(clockStyle);
            String time = buildTimeString((isVertical || isVerticalSS) ? "HH:MM" : clockStyle);

            String hour = "00", minute = "00", second = "";
            try {
                boolean use24 = SettingsManager.is24Hour(context);
                String hmPattern = use24 ? "HH:mm" : "hh:mm";
                if ("HHMM".equals(clockStyle)) {
                    String raw = DateFormat.format(hmPattern, new Date()).toString();
                    String[] p = raw.split(":");
                    hour = p[0]; minute = p.length > 1 ? p[1] : "00";
                } else if ("HH MM".equals(clockStyle)) {
                    // Space separator - split by space
                    String[] parts = time.split(" ");
                    hour = parts[0].trim(); minute = parts.length > 1 ? parts[1].trim() : "00";
                    // No seconds for HH MM style
                } else if ("HH.MM".equals(clockStyle)) {
                    String[] parts = time.split("\\.");
                    hour = parts[0].trim(); minute = parts.length > 1 ? parts[1].trim() : "00";
                } else if ("HH/MM".equals(clockStyle)) {
                    String[] parts = time.split("/");
                    hour = parts[0].trim(); minute = parts.length > 1 ? parts[1].trim() : "00";
                } else if ("HH/MM/SS".equals(clockStyle)) {
                    // Parse HH/MM/SS format
                    String[] parts = time.split("/");
                    hour = parts[0].trim();
                    minute = parts.length > 1 ? parts[1].trim() : "00";
                    second = parts.length > 2 ? parts[2].trim() : "00";
                } else if ("HH:MM:SS".equals(clockStyle)) {
                    String[] parts = time.split(":");
                    hour = parts[0].trim();
                    minute = parts.length > 1 ? parts[1].trim() : "00";
                    second = parts.length > 2 ? parts[2].trim() : "00";
                } else if (isVertical || isVerticalSS) {
                    String raw = DateFormat.format(hmPattern, new Date()).toString();
                    String[] p = raw.split(":");
                    hour = p[0]; minute = p.length > 1 ? p[1] : "00";
                    if (isVerticalSS) {
                        second = DateFormat.format("ss", new Date()).toString();
                    }
                } else {
                    // Default HH:MM - split by colon, no seconds
                    String[] parts = time.split(":");
                    hour = parts[0].trim(); minute = parts.length > 1 ? parts[1].trim() : "00";
                }
            } catch (Exception ignored) {}

            float baseX    = (float) timeObj.optDouble("x", 0.5) * cW;
            float baseY    = (float) timeObj.optDouble("y", 0.65) * cH;
            float size     = (float) timeObj.optDouble("size", 520);
            float rotation = (float) timeObj.optDouble("rotation", -5);
            String fontName  = timeObj.optString("font", "main3.ttf");
            String hourColor = timeObj.optString("hourColor", "#FFFFFF");
            String minColor  = timeObj.optString("minuteColor", "#FF5FA2");
            float opacity    = (float) timeObj.optDouble("opacity", 1.0);
            float ls         = (float) timeObj.optDouble("letterSpacing", 0);
            // Map -100..+100 to a reasonable range for Paint.setLetterSpacing (~-0.15 .. +0.3)
            float letterSpacingNorm = Math.max(-100f, Math.min(100f, ls));
            float paintLetterSpacing = (letterSpacingNorm / 100f) * 0.30f; // 0.30 is wide, -0.30 is very tight

            String glowColor = timeObj.optString("glowColor", null);
            float glowRadius = (float)timeObj.optDouble("glowRadius", 0);
            if (glowColor == null || glowColor.isEmpty()) glowColor = hourColor;
            // Shadow
            boolean shadowEnabled = timeObj.optBoolean("shadowEnabled", false);
            float shadowX = (float) timeObj.optDouble("shadowX", 4);
            float shadowY2 = (float) timeObj.optDouble("shadowY", 4);

            // Stroke / fill
            boolean strokeEnabled = timeObj.optBoolean("strokeEnabled", false);
            float strokeWidth  = strokeEnabled ? (float) timeObj.optDouble("strokeWidth", 3) : 0;
            String strokeColor = timeObj.optString("strokeColor", "#000000");
            // "both" | "hh" | "mm"
            String strokeTarget = timeObj.optString("strokeTarget", "both");
            boolean fillEnabled = timeObj.optBoolean("fillEnabled", true); // new: when false -> stroke only

            // Gradient
            boolean timeGradientEnabled = timeObj.optBoolean("timeGradientEnabled", false);
            float timeGradientAngle = (float) timeObj.optDouble("timeGradientAngle", 0f);

            // Stretch / skew
            float stretchX    = (float) timeObj.optDouble("stretchX", 1.0);
            float stretchY    = (float) timeObj.optDouble("stretchY", 1.0);
            float skewH       = (float) timeObj.optDouble("skewH", 0);
            float skewV       = (float) timeObj.optDouble("skewV", 0);
            float skewBottomH = (float) timeObj.optDouble("skewBottomH", 0);
            float skewLeftV   = (float) timeObj.optDouble("skewLeftV", 0);

            Typeface tf = loadFont(fontName);
            Paint hourPaint = makeBaseTextPaint(size, hourColor, opacity, fontName, paintLetterSpacing);
            Paint minPaint  = makeBaseTextPaint(size, minColor,  opacity, fontName, paintLetterSpacing);
            // Seconds use same size as hours/minutes
            Paint secPaint  = (isSeconds || isVerticalSS) ? makeBaseTextPaint(size, minColor, opacity, fontName, paintLetterSpacing) : null;

            applyLetterSpacing(hourPaint, minPaint, ls, size);

            // Apply linear gradient shader across entire time run if enabled
            if (timeGradientEnabled) {
                // Gradient runs along X axis; angle controls direction
                double rad = Math.toRadians(timeGradientAngle);
                float cos = (float) Math.cos(rad);
                float sin = (float) Math.sin(rad);
                // Use width of screen for gradient length
                float len = cW;
                float x0 = baseX - len * 0.5f * cos;
                float y0 = baseY - len * 0.5f * sin;
                float x1 = baseX + len * 0.5f * cos;
                float y1 = baseY + len * 0.5f * sin;
                int cStart = Color.parseColor(hourColor);
                int cEnd   = Color.parseColor(minColor);
                android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                        x0, y0, x1, y1,
                        new int[]{cStart, cEnd},
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                );
                hourPaint.setShader(lg);
                minPaint.setShader(lg);
                if (secPaint != null) secPaint.setShader(lg);
            }

            // Shadow paints - black text with same dimensions, drawn beneath main text
            Paint hourShadowPaint = null, minShadowPaint = null, secShadowPaint = null;
            if (shadowEnabled) {
                // Create dedicated shadow paints - solid black, same font/size
                hourShadowPaint = new Paint(hourPaint);
                hourShadowPaint.setShader(null);  // No gradient on shadow
                hourShadowPaint.clearShadowLayer();
                hourShadowPaint.setColor(Color.BLACK);
                hourShadowPaint.setAlpha((int)(opacity * 255));

                minShadowPaint = new Paint(minPaint);
                minShadowPaint.setShader(null);
                minShadowPaint.clearShadowLayer();
                minShadowPaint.setColor(Color.BLACK);
                minShadowPaint.setAlpha((int)(opacity * 255));

                if (secPaint != null) {
                    secShadowPaint = new Paint(secPaint);
                    secShadowPaint.setShader(null);
                    secShadowPaint.clearShadowLayer();
                    secShadowPaint.setColor(Color.BLACK);
                    secShadowPaint.setAlpha((int)(opacity * 255));
                }
            }

            // Stroke paints — respect strokeTarget, use explicit stroke color (no shader)
            Paint hourStrokePaint = null, minStrokePaint = null, secStrokePaint = null;
            int strokeColorParsed = Color.BLACK;
            try { strokeColorParsed = Color.parseColor(strokeColor); } catch (Exception ignored) {}

            if (strokeEnabled && strokeWidth > 0) {
                boolean applyHH = "both".equals(strokeTarget) || "hh".equals(strokeTarget);
                boolean applyMM = "both".equals(strokeTarget) || "mm".equals(strokeTarget);
                if (applyHH) {
                    hourStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                    hourStrokePaint.setTypeface(tf);
                    hourStrokePaint.setTextSize(size);
                    hourStrokePaint.setTextAlign(Paint.Align.LEFT);
                    hourStrokePaint.setStyle(Paint.Style.STROKE);
                    hourStrokePaint.setStrokeWidth(strokeWidth);
                    hourStrokePaint.setColor(strokeColorParsed);  // Explicit stroke color
                    hourStrokePaint.setAlpha((int)(opacity * 255));
                    // No shader - stroke is always solid color
                }
                if (applyMM) {
                    minStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                    minStrokePaint.setTypeface(tf);
                    minStrokePaint.setTextSize(size);
                    minStrokePaint.setTextAlign(Paint.Align.LEFT);
                    minStrokePaint.setStyle(Paint.Style.STROKE);
                    minStrokePaint.setStrokeWidth(strokeWidth);
                    minStrokePaint.setColor(strokeColorParsed);  // Explicit stroke color
                    minStrokePaint.setAlpha((int)(opacity * 255));

                    if (secPaint != null) {
                        secStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                        secStrokePaint.setTypeface(tf);
                        secStrokePaint.setTextSize(size);
                        secStrokePaint.setTextAlign(Paint.Align.LEFT);
                        secStrokePaint.setStyle(Paint.Style.STROKE);
                        secStrokePaint.setStrokeWidth(strokeWidth);
                        secStrokePaint.setColor(strokeColorParsed);  // Explicit stroke color
                        secStrokePaint.setAlpha((int)(opacity * 255));
                    }
                }
            }
            hourPaint.setSubpixelText(true);
            minPaint.setSubpixelText(true);

            Paint hourGlow = (glowRadius > 0) ? makeGlowPaint(hourPaint, glowColor, glowRadius) : null;
            Paint minGlow  = (glowRadius > 0) ? makeGlowPaint(minPaint,  glowColor, glowRadius) : null;
            Paint secGlow  = (glowRadius > 0 && secPaint != null) ? makeGlowPaint(secPaint, glowColor, glowRadius) : null;

            // Separator
            String sep = ":";
            if ("HHMM".equals(clockStyle) || isVertical || isVerticalSS) sep = "";
            else if ("HH MM".equals(clockStyle)) sep = " ";
            else if ("HH.MM".equals(clockStyle)) sep = ".";
            else if ("HH/MM".equals(clockStyle) || "HH/MM/SS".equals(clockStyle)) sep = "/";

            // consistent spacing between blocks
            final float extraGap = Math.max(6f, size * 0.06f);

            // depth mode
            String depthMode = timeObj.optString("depthMode", "none");
            // Connector behind mask setting (default true = behind mask)
            boolean connectorBehindMask = timeObj.optBoolean("connectorBehindMask", true);

            float hourW = hourPaint.measureText(hour);
            float sepW  = sep.isEmpty() ? 0f : hourPaint.measureText(sep);
            float minW  = minPaint.measureText(minute);
            float secW  = (secPaint != null) ? secPaint.measureText(second) : 0f;

            // For vertical layout: stacked HH / MM centered
            float totalW, startX, hourDrawX, minDrawX, sepMinX, secSepX, secDrawX, secBaseY;
            float hourDrawY = baseY, minDrawY = baseY;  // for vertical

            if (isVertical || isVerticalSS) {
                // Vertically stacked HH/MM(/SS), tight spacing so digits just touch
                float lineH = Math.abs(hourPaint.ascent()) + Math.abs(hourPaint.descent());
                hourDrawX = baseX;
                minDrawX  = baseX;
                // Use exactly 0.5 so the bottom of HH touches the top of MM
                hourDrawY = isVerticalSS ? baseY - lineH : baseY - lineH * 0.5f;
                minDrawY  = isVerticalSS ? baseY          : baseY + lineH * 0.5f;
                // seconds are drawn below MM when VERTICAL_SS
                sepMinX = baseX; secSepX = 0; secDrawX = 0; secBaseY = baseY + lineH;
            } else {
                totalW = hourW
                        + (sep.isEmpty() ? 0 : sepW + extraGap) + minW + extraGap
                        + (isSeconds ? (sepW + extraGap + secW) : 0);
                startX = baseX - totalW / 2f;
                hourDrawX = startX;
                minDrawX  = startX + hourW + (sep.isEmpty() ? 0 : sepW + extraGap) + extraGap;
                sepMinX   = startX + hourW + extraGap / 2f;
                secSepX   = minDrawX + minW + extraGap * 0.3f;
                secDrawX  = secSepX + sepW + extraGap * 0.2f;
                secBaseY  = baseY;  // same baseline
            }

            // ── Date setup ──
            String dateStr = "";
            float dateX = baseX, dateY = baseY + size * 0.15f;
            float dateSize2 = Math.max(24f, cW / 20f), dateRot = 0f;
            Paint datePaint = null;

            if (dateVisible && dateObj != null) {
                dateStr  = DateFormat.format(dateObj.optString("format", "EEE, dd MMM"), new Date()).toString();
                if (dateObj.optBoolean("allCaps", false)) dateStr = dateStr.toUpperCase();
                dateX    = (float) dateObj.optDouble("x", 0.5) * cW;
                dateY    = (float) dateObj.optDouble("y", 0.75) * cH;
                dateSize2 = (float) dateObj.optDouble("size", Math.max(24f, cW / 20f));
                dateRot  = (float) dateObj.optDouble("rotation", 0);
                Typeface dtf = loadFont(dateObj.optString("font", "main3.ttf"));
                datePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                datePaint.setTypeface(dtf); datePaint.setTextSize(dateSize2);
                datePaint.setColor(Color.parseColor(dateObj.optString("color", "#FFFFFF")));
                datePaint.clearShadowLayer(); datePaint.setTextAlign(Paint.Align.CENTER);
                datePaint.setAlpha((int)((float) dateObj.optDouble("opacity", 1.0) * 255));
            }

            float pivotX = baseX;
            float pivotY = baseY;  // pivot is time position; date is drawn independently

            // ── Depth mode logic ──
            // "none"        → draw all in one pass
            // "hoursFront"  → back=minute, front=hour
            // "minuteFront" → back=hour,   front=minute
            boolean depthActive = !"none".equals(depthMode) && !"all".equals(layerPass);
            boolean drawHour   = true, drawMinute = true;
            if (depthActive) {
                if ("hoursFront".equals(depthMode)) {
                    drawHour   = "front".equals(layerPass);
                    drawMinute = "back".equals(layerPass);
                } else { // minuteFront
                    drawHour   = "back".equals(layerPass);
                    drawMinute = "front".equals(layerPass);
                }
            }
            // Date always draws in back layer (or all)
            boolean drawDate = "all".equals(layerPass) || "back".equals(layerPass);

            // ── Animation ──
            int   layerAlpha = 255;
            float animTX = 0, animTY = 0, animSX = 1, animSY = 1;
            boolean needsLayer = (animPhase < 0.999f);
            if (needsLayer) {
                float inv = 1f - animPhase; float travel = size * 0.9f;
                layerAlpha = Math.max(8, Math.min(255, (int)(255f * animPhase)));
                switch (animStyle) {
                    case ANIM_FADE_SCALE:     animSX = animSY = 0.72f + 0.28f * animPhase; break;
                    case ANIM_SLIDE_UP:       animTY =  travel * inv; break;
                    case ANIM_SLIDE_DOWN:     animTY = -travel * inv; break;
                    case ANIM_SLIDE_LEFT:     animTX =  travel * inv; break;
                    case ANIM_SLIDE_RIGHT:    animTX = -travel * inv; break;
                    case ANIM_STRETCH_VERT:   animSY = 0.1f + 0.9f * animPhase; break;
                    case ANIM_STRETCH_VERT_LN:
                        animSY = Math.max(0.05f, (float) Math.pow(animPhase, 0.4));
                        layerAlpha = Math.max(8, Math.min(255, (int)(255f * (float) Math.pow(animPhase, 0.5))));
                        break;
                    case ANIM_PARALLAX_DRIFT: animTX = 20f * inv; animTY = 80f * inv; break;
                    default: animSX = animSY = 0.72f + 0.28f * animPhase; break;
                }
            }

            if (needsLayer) {
                Paint lp = new Paint(); lp.setAlpha(layerAlpha);
                canvas.saveLayer(new android.graphics.RectF(0, 0, cW, cH), lp);
            } else {
                canvas.save();
            }
            if (animTX != 0 || animTY != 0) canvas.translate(animTX, animTY);
            if (animSX != 1 || animSY != 1) canvas.scale(animSX, animSY, pivotX, pivotY);

                // ── Draw date FIRST (with gyro but without time's stretch/skew/rotation) ──
            if (drawDate && datePaint != null && !dateStr.isEmpty()) {
                canvas.save();
                // Apply gyro motion to date separately
                applyMotionTransform(canvas, dateX, dateY, pitch, roll, offX, offY, motionMode, gyroActive);
                if (dateRot != 0f) {
                    canvas.rotate(dateRot, dateX, dateY);
                }
                canvas.drawText(dateStr, dateX, dateY, datePaint);
                canvas.restore();
            }

            // ── Apply time-only transforms ──
            canvas.save();
            if (stretchX != 1f || stretchY != 1f) canvas.scale(stretchX, stretchY, baseX, baseY);

            // 3D Skew (tilt) instead of 2D skew:
            // skewH tilts around X axis (top/back vs bottom/front) => XY -> XZ feel
            // skewV tilts around Y axis (left/back vs right/front)  => XY -> YZ feel
            // Values are -0.5..+0.5 in UI; convert to degrees.
            float skewPitchDeg = (float) (skewH * 55f); // forward/back
            float skewRollDeg  = (float) (skewV * 55f); // left/right

            // Bottom/left skews are still applied as subtle 2D skew (kept for now)
            if (Math.abs(skewBottomH) > 0.0001f || Math.abs(skewLeftV) > 0.0001f) {
                Matrix m2 = new Matrix();
                m2.setSkew(skewBottomH, skewLeftV, baseX, baseY);
                canvas.concat(m2);
            }

            if (Math.abs(skewPitchDeg) > 0.01f || Math.abs(skewRollDeg) > 0.01f) {
                android.graphics.Camera cam = new android.graphics.Camera();
                cam.save();
                // Similar to gyro 3D: closer camera makes effect stronger
                cam.setLocation(0f, 0f, -8f);
                cam.rotateX(skewPitchDeg);
                cam.rotateY(skewRollDeg);
                Matrix m3 = new Matrix();
                cam.getMatrix(m3);
                cam.restore();
                m3.preTranslate(-baseX, -baseY);
                m3.postTranslate(baseX, baseY);
                canvas.concat(m3);
            }

            applyMotionTransform(canvas, pivotX, pivotY, pitch, roll, offX, offY, motionMode, gyroActive);
            if (rotation != 0f) canvas.rotate(rotation, baseX, baseY);

            // Determine if we need a fallback - if fill disabled and shadow disabled and no stroke, draw fill anyway
            boolean needsFallback = !fillEnabled && !shadowEnabled && !strokeEnabled;

            // Curvature: -1..1 (UI can map to stronger), 0 = flat
            float curvature = (float) timeObj.optDouble("curvature", 0.0);

            if (Math.abs(curvature) > 0.001f) {
                float cur = clamp(curvature, -1.0f, 1.0f);
                boolean isSmile = (cur > 0);

                // Wider/safer math: radius is ~ screenWidth / |cur|.
                // This prevents the path from shrinking too much and collapsing the text.
                float ref = Math.max(cW, size * 6f);
                float radius = ref / Math.max(0.08f, Math.abs(cur));
                radius = Math.max(radius, size * 1.2f);

                // Place circle center so the arc's vertex passes through (baseX, baseY).
                // For a smile the vertex is the bottom of the circle; for a frown it's the top.
                float circleX = baseX;
                float circleY = isSmile ? (baseY - radius) : (baseY + radius);

                // Compute total width for proper centering.
                float arcTotalW = 0f;
                if (drawHour) arcTotalW += hourPaint.measureText(hour);

                // HH:MM connector participates only when it is drawn in this pass
                boolean drawHourMinSep;
                if ("none".equals(depthMode)) {
                    drawHourMinSep = "all".equals(layerPass);
                } else if ("minuteFront".equals(depthMode)) {
                    drawHourMinSep = "all".equals(layerPass) || "front".equals(layerPass);
                } else {
                    drawHourMinSep = "all".equals(layerPass) || "back".equals(layerPass);
                }
                if (!sep.isEmpty() && drawHourMinSep) arcTotalW += hourPaint.measureText(sep);

                if (drawMinute) arcTotalW += minPaint.measureText(minute);

                boolean drawSecSep = false;
                if (isSeconds && secPaint != null) {
                    if ("none".equals(depthMode)) {
                        drawSecSep = "all".equals(layerPass);
                    } else if (connectorBehindMask) {
                        drawSecSep = "all".equals(layerPass) || "back".equals(layerPass);
                    } else {
                        drawSecSep = "all".equals(layerPass) || "front".equals(layerPass);
                    }
                    if (!sep.isEmpty() && drawSecSep) arcTotalW += secPaint.measureText(sep);
                    if (drawMinute || "all".equals(layerPass)) arcTotalW += secPaint.measureText(second);
                }

                // Build an arc that is long enough for the text.
                // Keep a minimum sweep so spacing doesn't get weird for short strings.
                float circum = 2f * (float) Math.PI * radius;
                float requiredSweep = Math.max(12f, (arcTotalW / circum) * 360f);
                float sweepPad = 10f;
                float sweep = Math.min(220f, requiredSweep + sweepPad);

                // Anchor around the vertex: bottom (90°) for smile, top (270°) for frown.
                float anchor = isSmile ? 90f : 270f;
                // For smile we want left->right along the bottom: use CCW (negative sweep).
                // For frown we want left->right along the top: use CW (positive sweep).
                float start = isSmile ? (anchor + sweep / 2f) : (anchor - sweep / 2f);
                float sweepDir = isSmile ? -sweep : sweep;

                android.graphics.Path arc = new android.graphics.Path();
                android.graphics.RectF oval = new android.graphics.RectF(circleX - radius, circleY - radius,
                        circleX + radius, circleY + radius);
                arc.addArc(oval, start, sweepDir);

                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(arc, false);
                float pathLen = pm.getLength();
                float startOffset = Math.max(0f, (pathLen - arcTotalW) / 2f);

                // Mutable offset helper
                class ArcDrawer {
                    float off;
                    ArcDrawer(float start) { off = start; }
                    void skip(Paint p, String txt) {
                        if (p == null || txt == null) return;
                        off += p.measureText(txt);
                    }
                    void draw(Paint p, String txt) {
                        if (p == null || txt == null || txt.isEmpty()) return;
                        canvas.drawTextOnPath(txt, arc, off, 0, p);
                        off += p.measureText(txt);
                    }
                    void drawShadow(Paint shadowPaint, Paint measurePaint, String txt, float dx, float dy) {
                        if (shadowPaint == null || measurePaint == null || txt == null || txt.isEmpty()) return;
                        float start = off;
                        canvas.save();
                        canvas.translate(dx, dy);
                        canvas.drawTextOnPath(txt, arc, start, 0, shadowPaint);
                        canvas.restore();
                        off = start + measurePaint.measureText(txt);
                    }
                }
                ArcDrawer ad = new ArcDrawer(startOffset);

                // Hour
                if (drawHour) {
                    if (hourGlow != null) ad.draw(hourGlow, hour);
                    if (hourStrokePaint != null) ad.draw(hourStrokePaint, hour);
                    if (fillEnabled || needsFallback) ad.draw(hourPaint, hour);
                    if (hourShadowPaint != null) ad.drawShadow(hourShadowPaint, hourPaint, hour, shadowX, shadowY2);
                } else {
                    ad.skip(hourPaint, hour);
                }

                // HH:MM separator (follows minute layering)
                if (!sep.isEmpty()) {
                    if (drawHourMinSep) {
                        if (strokeEnabled && "both".equals(strokeTarget) && minStrokePaint != null) ad.draw(minStrokePaint, sep);
                        if (fillEnabled || needsFallback) ad.draw(hourPaint, sep);
                        if (hourShadowPaint != null) ad.drawShadow(hourShadowPaint, hourPaint, sep, shadowX, shadowY2);
                    } else {
                        ad.skip(hourPaint, sep);
                    }
                }

                // Minute
                if (drawMinute) {
                    if (minGlow != null) ad.draw(minGlow, minute);
                    if (minStrokePaint != null) ad.draw(minStrokePaint, minute);
                    if (fillEnabled || needsFallback) ad.draw(minPaint, minute);
                    if (minShadowPaint != null) ad.drawShadow(minShadowPaint, minPaint, minute, shadowX, shadowY2);
                } else {
                    ad.skip(minPaint, minute);
                }

                // Seconds
                if (isSeconds && secPaint != null) {
                    if (!sep.isEmpty()) {
                        if (drawSecSep) {
                            if (strokeEnabled && "both".equals(strokeTarget) && secStrokePaint != null) ad.draw(secStrokePaint, sep);
                            if (fillEnabled || needsFallback) ad.draw(secPaint, sep);
                            if (secShadowPaint != null) ad.drawShadow(secShadowPaint, secPaint, sep, shadowX, shadowY2);
                        } else {
                            ad.skip(secPaint, sep);
                        }
                    }

                    if (drawMinute || "all".equals(layerPass)) {
                        if (secGlow != null) ad.draw(secGlow, second);
                        if (secStrokePaint != null) ad.draw(secStrokePaint, second);
                        if (fillEnabled || needsFallback) ad.draw(secPaint, second);
                        if (secShadowPaint != null) ad.drawShadow(secShadowPaint, secPaint, second, shadowX, shadowY2);
                    }
                }

                canvas.restore();
                canvas.restore();
                return;
            }

            // ── Draw hour ──
            if (drawHour) {
                if (hourGlow != null)       canvas.drawText(hour, hourDrawX, hourDrawY, hourGlow);
                if (hourStrokePaint != null) canvas.drawText(hour, hourDrawX, hourDrawY, hourStrokePaint);
                if (fillEnabled || needsFallback) canvas.drawText(hour, hourDrawX, hourDrawY, hourPaint);
                // Black shadow text drawn ABOVE the normal text
                if (hourShadowPaint != null) {
                    canvas.drawText(hour, hourDrawX + shadowX, hourDrawY + shadowY2, hourShadowPaint);
                }
            }

            // ── Draw HH:MM separator - follows minute's depth layering ──
            // If depthMode is "minuteFront", minute is in front, so HH:MM connector should also be in front
            // If depthMode is "hoursFront", minute is in back, so HH:MM connector should be in back
            // If depthMode is "none", draw in all pass
            boolean drawHourMinSeparator;
            if ("none".equals(depthMode)) {
                drawHourMinSeparator = "all".equals(layerPass);
            } else if ("minuteFront".equals(depthMode)) {
                // Minute is in front layer, so connector follows minute (front layer)
                drawHourMinSeparator = "all".equals(layerPass) || "front".equals(layerPass);
            } else {
                // hoursFront or other: minute is in back layer, so connector follows minute (back layer)
                drawHourMinSeparator = "all".equals(layerPass) || "back".equals(layerPass);
            }

            if (!sep.isEmpty() && drawHourMinSeparator) {
                // Stroke for connector follows minute when strokeTarget == both
                if (strokeEnabled && "both".equals(strokeTarget) && minStrokePaint != null) {
                    canvas.drawText(sep, sepMinX, baseY, minStrokePaint);
                }
                // Connector respects fillEnabled - only draw if fill is enabled (or fallback)
                if (fillEnabled || needsFallback) {
                    canvas.drawText(sep, sepMinX, baseY, hourPaint);
                }
                // Shadow for HH:MM connector
                if (hourShadowPaint != null) {
                    canvas.drawText(sep, sepMinX + shadowX, baseY + shadowY2, hourShadowPaint);
                }
            }

            if (drawMinute) {
                if (minGlow != null)        canvas.drawText(minute, minDrawX, minDrawY, minGlow);
                if (minStrokePaint != null)  canvas.drawText(minute, minDrawX, minDrawY, minStrokePaint);
                if (fillEnabled || needsFallback) canvas.drawText(minute, minDrawX, minDrawY, minPaint);
                // Black shadow text drawn ABOVE the normal text
                if (minShadowPaint != null) {
                    canvas.drawText(minute, minDrawX + shadowX, minDrawY + shadowY2, minShadowPaint);
                }
            }

            // ── Draw seconds (HH:MM:SS or HH/MM/SS) ──
            if (isSeconds && secPaint != null) {

                // MM:SS separator - uses the connectorBehindMask toggle (only for second connector)
                boolean drawSecSeparator;
                if ("none".equals(depthMode)) {
                    // No depth mode - respect toggle but draw in appropriate pass
                    drawSecSeparator = "all".equals(layerPass);
                } else if (connectorBehindMask) {
                    // Second connector behind mask
                    drawSecSeparator = "all".equals(layerPass) || "back".equals(layerPass);
                } else {
                    // Second connector in front of mask
                    drawSecSeparator = "all".equals(layerPass) || "front".equals(layerPass);
                }

                if (!sep.isEmpty() && drawSecSeparator) {
                    // Stroke for MM:SS connector
                    if (strokeEnabled && "both".equals(strokeTarget) && secStrokePaint != null) {
                        canvas.drawText(sep, secSepX, baseY, secStrokePaint);
                    }
                    // Connector respects fillEnabled (or fallback)
                    if (fillEnabled || needsFallback) {
                        canvas.drawText(sep, secSepX, baseY, secPaint);
                    }
                    // Shadow for MM:SS connector
                    if (secShadowPaint != null) {
                        canvas.drawText(sep, secSepX + shadowX, baseY + shadowY2, secShadowPaint);
                    }
                }

                // Seconds digit - draw when minute draws or in all pass
                if (drawMinute || "all".equals(layerPass)) {
                    if (secGlow != null) canvas.drawText(second, secDrawX, secBaseY, secGlow);
                    if (secStrokePaint != null) canvas.drawText(second, secDrawX, secBaseY, secStrokePaint);
                    if (fillEnabled || needsFallback) canvas.drawText(second, secDrawX, secBaseY, secPaint);
                    // Black shadow text drawn ABOVE the normal text
                    if (secShadowPaint != null) {
                        canvas.drawText(second, secDrawX + shadowX, secBaseY + shadowY2, secShadowPaint);
                    }
                }
            }

            // ── Draw seconds for VERTICAL_SS (stacked below MM) ──
            if (isVerticalSS && secPaint != null) {
                if (drawMinute || "all".equals(layerPass)) {
                    if (secGlow != null)        canvas.drawText(second, minDrawX, secBaseY, secGlow);
                    if (secStrokePaint != null)  canvas.drawText(second, minDrawX, secBaseY, secStrokePaint);
                    if (fillEnabled || needsFallback) canvas.drawText(second, minDrawX, secBaseY, secPaint);
                    if (secShadowPaint != null) {
                        canvas.drawText(second, minDrawX + shadowX, secBaseY + shadowY2, secShadowPaint);
                    }
                }
            }

            canvas.restore(); // restore time transform
            canvas.restore(); // restore animation/layer

        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── renderDateWithMotion: standalone (used when time is hidden) ──

    private void renderDateWithMotion(Canvas canvas, JSONObject dateObj, int cW, int cH,
                                       float offX, float offY, float pitch, float roll, int motionMode,
                                       boolean gyroActive, float animPhase, int animStyle) {
        try {
            String dateStr = DateFormat.format(dateObj.optString("format", "EEE, dd MMM"), new Date()).toString();
            if (dateObj.optBoolean("allCaps", false)) dateStr = dateStr.toUpperCase();
            float x    = (float) dateObj.optDouble("x", 0.5) * cW;
            float y    = (float) dateObj.optDouble("y", 0.75) * cH;
            float size = (float) dateObj.optDouble("size", Math.max(24f, cW / 20f));
            float rot  = (float) dateObj.optDouble("rotation", 0);
            Typeface tf = loadFont(dateObj.optString("font", "main3.ttf"));

            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            dp.setTypeface(tf); dp.setTextSize(size);
            dp.setColor(Color.parseColor(dateObj.optString("color", "#FFFFFF")));
            dp.setAlpha((int)(dateObj.optDouble("opacity", 1.0) * 255));
            dp.clearShadowLayer(); dp.setTextAlign(Paint.Align.CENTER);

            // Compute animation
            int layerAlpha = 255;
            float tX = 0f, tY = 0f, sX = 1f, sY = 1f;
            boolean needsLayer = (animPhase < 0.999f);
            if (needsLayer) {
                float inv = 1f - animPhase;
                float travel = size * 0.9f;
                layerAlpha = Math.max(8, Math.min(255, (int)(255f * animPhase)));
                switch (animStyle) {
                    case ANIM_FADE_SCALE:       sX = sY = 0.72f + 0.28f * animPhase; break;
                    case ANIM_SLIDE_UP:         tY =  travel * inv; break;
                    case ANIM_SLIDE_DOWN:       tY = -travel * inv; break;
                    case ANIM_SLIDE_LEFT:       tX =  travel * inv; break;
                    case ANIM_SLIDE_RIGHT:      tX = -travel * inv; break;
                    case ANIM_STRETCH_VERT:     sY = 0.1f + 0.9f * animPhase; break;
                    case ANIM_STRETCH_VERT_LN:  sY = Math.max(0.05f, (float)Math.pow(animPhase, 0.4)); layerAlpha = Math.max(8, (int)(255f * (float)Math.pow(animPhase, 0.5))); break;
                    case ANIM_PARALLAX_DRIFT:   tX = 20f * inv; tY = 80f * inv; break;
                    default:                    sX = sY = 0.72f + 0.28f * animPhase; break;
                }
            }

            if (needsLayer) {
                Paint layerPaint = new Paint();
                layerPaint.setAlpha(layerAlpha);
                canvas.saveLayer(new android.graphics.RectF(0, 0, cW, cH), layerPaint);
            } else {
                canvas.save();
            }

            if (tX != 0f || tY != 0f) canvas.translate(tX, tY);
            if (sX != 1f || sY != 1f) canvas.scale(sX, sY, x, y);
            applyMotionTransform(canvas, x, y, pitch, roll, offX, offY, motionMode, gyroActive);
            if (rot != 0f) canvas.rotate(rot, x, y);
            canvas.drawText(dateStr, x, y, dp);
            canvas.restore();

        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Motion transform ─────────────────────────────────────────────────────

    private void applyMotionTransform(Canvas canvas, float pivotX, float pivotY,
                                       float pitch, float roll, float offX, float offY,
                                       int motionMode, boolean gyroActive) {
        if (!gyroActive) return;

        if (motionMode == 0) {
            // 3D tilt — Camera perspective projection
            float degPitch = (float) Math.toDegrees(pitch);  // forward/backward tilt
            float degRoll  = (float) Math.toDegrees(roll);   // left/right tilt

            // Increased max angles for more visible 3D effect
            float maxPitchAngle = 25f;
            float maxRollAngle  = 22f;

            // rotateX: tilt forward/backward - when phone tilts forward, text appears to recede
            // Inverted sign so tilting phone forward makes text tilt back naturally
            float camRotX = Math.max(-maxPitchAngle, Math.min(maxPitchAngle, degPitch * 1.2f));
            // rotateY: left/right parallax
            float camRotY = Math.max(-maxRollAngle,  Math.min(maxRollAngle,  degRoll * 0.9f));

            android.graphics.Camera camera = new android.graphics.Camera();
            camera.save();
            // Closer camera = more dramatic perspective effect
            camera.setLocation(0f, 0f, -8f);
            camera.rotateX(camRotX);
            camera.rotateY(camRotY);
            Matrix m = new Matrix();
            camera.getMatrix(m);
            camera.restore();

            // Pivot at text center for balanced rotation
            m.preTranslate(-pivotX, -pivotY);
            m.postTranslate(pivotX, pivotY);
            canvas.concat(m);
        } else {
            // Shift mode - translate based on gyro
            canvas.translate(offX, offY);
        }
    }


    // ── Paint helpers ────────────────────────────────────────────────────────

    private Paint makePaint(Typeface tf, float size, String color, float opacity, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(Color.parseColor(color));
        p.setAlpha((int)(opacity * 255));
        p.setTextAlign(align);
        p.clearShadowLayer();
        p.setFakeBoldText(false);
        return p;
    }

    private void applyLetterSpacing(Paint a, Paint b, float ls, float size) {
        if (size <= 0 || Math.abs(ls) < 0.0001f) return;
        float em = Math.max(-0.5f, Math.min(1f, ls / size));
        a.setLetterSpacing(em);
        b.setLetterSpacing(em);
    }

    private Paint makeGlowPaint(Paint base, String glowColor, float radius) {
        Paint g = new Paint(base);
        g.setStyle(Paint.Style.FILL);
        try { g.setColor(Color.parseColor(glowColor)); } catch (Exception e) { g.setColor(Color.WHITE); }
        g.setAlpha(Math.min(base.getAlpha(), 200));  // respect opacity
        if (radius > 0) g.setMaskFilter(new android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL));
        return g;
    }

    private Paint makeOutlinePaint(Paint base, float width) {
        Paint p = new Paint(base);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(Color.BLACK);
        p.setMaskFilter(null);
        return p;
    }

    // ── Font loading ─────────────────────────────────────────────────────────

    private String buildTimeString(String clockStyle) {
        try {
            boolean use24 = SettingsManager.is24Hour(context);
            switch (clockStyle) {
                case "HHMM":     return DateFormat.format(use24 ? "HHmm" : "hhmm", new Date()).toString();
                case "HH MM":    return DateFormat.format(use24 ? "HH mm" : "hh mm", new Date()).toString();
                case "HH.MM":    return DateFormat.format(use24 ? "HH.mm" : "hh.mm", new Date()).toString();
                case "HH:MM:SS": return DateFormat.format(use24 ? "HH:mm:ss" : "hh:mm:ss", new Date()).toString();
                case "HH/MM":    return DateFormat.format(use24 ? "HH/mm" : "hh/mm", new Date()).toString();
                case "HH/MM/SS": return DateFormat.format(use24 ? "HH/mm/ss" : "hh/mm/ss", new Date()).toString();
                case "VERTICAL":
                case "VERTICAL_SS": return DateFormat.format(use24 ? "HH:mm" : "hh:mm", new Date()).toString();
                default:         return DateFormat.format(use24 ? "HH:mm" : "hh:mm", new Date()).toString();
            }
        } catch (Exception e) { return "00:00"; }
    }

    private String getTimeFormat() {
        return SettingsManager.is24Hour(context) ? "HH:mm" : "hh:mm a";
    }

    private Typeface loadFont(String fontName) {
        Typeface tf = null;
        try {
            if (fontCache.containsKey(fontName)) return fontCache.get(fontName);
            for (String path : new String[]{"fonts/" + fontName, fontName}) {
                try {
                    tf = Typeface.createFromAsset(context.getAssets(), path);
                    fontCache.put(fontName, tf);
                    return tf;
                } catch (Exception ignored) {}
            }
            for (String fb : new String[]{
                    "main1.ttf","main2.ttf","main3.ttf","main.ttf","Font-1.ttf",
                    "fun1.ttf","fun2.ttf","fun3.ttf","fun4.ttf","fun5.ttf",
                    "pine1.ttf","pine2.ttf","pine3.ttf","pine4.ttf",
                    "apple1.ttf","apple2.ttf","apple3.ttf","apple4.ttf","apple5.ttf"}) {
                try {
                    tf = Typeface.createFromAsset(context.getAssets(), "fonts/" + fb);
                    fontCache.put(fontName, tf);
                    return tf;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return Typeface.DEFAULT_BOLD;
    }

    // ── Fallback simple bitmap ────────────────────────────────────────────────

    private Bitmap createSimpleTimeBitmap(String time, int bmpW, int bmpH) {
        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(loadFont("main3.ttf"));
        tp.setTextSize(450);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);

        float cx = bmpW / 2f, ty = bmpH * 0.45f;
        canvas.drawText(time, cx, ty, tp);

        Paint dp = new Paint(tp);
        dp.setTextSize(Math.max(24, bmpW / 20));
        String dateStr = DateFormat.format("EEE, dd MMM", new Date()).toString();
        float dy = ty + (Math.abs(tp.ascent()) + Math.abs(tp.descent())) * 0.9f;
        canvas.drawText(dateStr, cx, dy, dp);
        return bmp;
    }

    // Legacy stubs kept for any remaining callers
    private void renderTime(Canvas canvas, JSONObject timeObj, int cW, int cH, float mX, float mY) {
        renderTimeFull(canvas, timeObj, null, false, cW, cH, mX, mY, 0, 0, -1, false, 1f, ANIM_FADE_SCALE, "all");
    }

    private void renderDate(Canvas canvas, JSONObject dateObj, int cW, int cH) {
        renderDateWithMotion(canvas, dateObj, cW, cH, 0, 0, 0, 0, -1, false, 1f, ANIM_FADE_SCALE);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private Paint makeBaseTextPaint(float size, String color, float opacity, String fontName, float letterSpacing) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        Typeface tf = loadFont(fontName);
        p.setTypeface(tf);
        p.setTextSize(size);
        try { p.setColor(Color.parseColor(color)); } catch (Exception ignored) { p.setColor(Color.WHITE); }
        p.setAlpha((int)(opacity * 255));
        p.setTextAlign(Paint.Align.LEFT);
        p.clearShadowLayer();
        if (letterSpacing != 0f) {
            try { p.setLetterSpacing(letterSpacing); } catch (Throwable ignored) {}
        }
        return p;
    }
}
