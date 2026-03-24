package com.infinity.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Colorful cloud firework:
 *  - Phase 1 (0.00 → 0.55): Vivid multicolor cloud puffs explode from center,
 *    spreading outward like a firework but as soft volumetric clouds.
 *  - Phase 2 (0.30 → 1.00): Black smoke cloud slowly expands from center,
 *    swallowing the color cloud. Grows organically — no hard edges.
 *  - Hold 500ms fully black → launch.
 *
 * ZERO lines. Everything is large blurred circles / ovals giving pure cloud look.
 */
public class MistBackgroundView extends View {

    private static final long BURST_MS = 1350L;
    private static final long HOLD_MS  =  500L;

    // Vivid color palette for the firework cloud
    private static final int[] COLORS = {
            0xFFFF2D2D,  // red
            0xFFE84C3D,  // accent red
            0xFFFF6B55,  // light red
            0xFFCC1A0A,  // deep red
            0xFFFF4444,  // bright red
            0xFF8B0000,  // dark red
            0xFFFF3333,  // vivid red
            0xFFD43020,  // crimson
    };

    // ── Color cloud puffs ─────────────────────────────────────────────────
    // Large puffs: big slow blobs that define the shape
    private static final int BIG_COUNT = 40;
    private final float[] bigAngle  = new float[BIG_COUNT];
    private final float[] bigSpeed  = new float[BIG_COUNT];
    private final float[] bigR      = new float[BIG_COUNT]; // base radius
    private final float[] bigGrow   = new float[BIG_COUNT];
    private final float[] bigAspW   = new float[BIG_COUNT]; // oval width multiplier
    private final float[] bigAspH   = new float[BIG_COUNT]; // oval height multiplier
    private final float[] bigRot    = new float[BIG_COUNT]; // oval rotation
    private final float[] bigPhase  = new float[BIG_COUNT];
    private final float[] bigTurbA  = new float[BIG_COUNT]; // turbulence amplitude
    private final float[] bigTurbF  = new float[BIG_COUNT]; // turbulence frequency
    private final float[] bigAlpha  = new float[BIG_COUNT];
    private final float[] bigDelay  = new float[BIG_COUNT];
    private final int[]   bigColor  = new int[BIG_COUNT];

    // Small puffs: fill gaps, add texture
    private static final int SMALL_COUNT = 60;
    private final float[] smAngle  = new float[SMALL_COUNT];
    private final float[] smSpeed  = new float[SMALL_COUNT];
    private final float[] smR      = new float[SMALL_COUNT];
    private final float[] smGrow   = new float[SMALL_COUNT];
    private final float[] smPhase  = new float[SMALL_COUNT];
    private final float[] smTurbA  = new float[SMALL_COUNT];
    private final float[] smTurbF  = new float[SMALL_COUNT];
    private final float[] smAlpha  = new float[SMALL_COUNT];
    private final float[] smDelay  = new float[SMALL_COUNT];
    private final int[]   smColor  = new int[SMALL_COUNT];

    // ── Black smoke puffs ─────────────────────────────────────────────────
    // Follow same angles as color puffs but slower, bigger, appear later
    private static final int BLACK_COUNT = 55;
    private final float[] blAngle  = new float[BLACK_COUNT];
    private final float[] blSpeed  = new float[BLACK_COUNT];
    private final float[] blR      = new float[BLACK_COUNT];
    private final float[] blGrow   = new float[BLACK_COUNT];
    private final float[] blPhase  = new float[BLACK_COUNT];
    private final float[] blTurbA  = new float[BLACK_COUNT];
    private final float[] blTurbF  = new float[BLACK_COUNT];
    private final float[] blAlpha  = new float[BLACK_COUNT];
    private final float[] blDelay  = new float[BLACK_COUNT]; // all start after 0.25

    // Central black origin bloom (grows from center outward, very large)
    private static final int BLOOM_COUNT = 12;
    private final float[] boR      = new float[BLOOM_COUNT];
    private final float[] boOffX   = new float[BLOOM_COUNT];
    private final float[] boOffY   = new float[BLOOM_COUNT];
    private final float[] boDelay  = new float[BLOOM_COUNT];
    private final float[] boAlpha  = new float[BLOOM_COUNT];

    // ── Paints ────────────────────────────────────────────────────────────
    private final Paint bigPaint   = new Paint(Paint.ANTI_ALIAS_FLAG); // 38dp blur
    private final Paint smPaint    = new Paint(Paint.ANTI_ALIAS_FLAG); // 20dp blur
    private final Paint blPaint    = new Paint(Paint.ANTI_ALIAS_FLAG); // 50dp blur — black
    private final Paint boPaint    = new Paint(Paint.ANTI_ALIAS_FLAG); // 65dp blur — black bloom
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidBlack = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── State ─────────────────────────────────────────────────────────────
    private float   t = 0f;
    private float   cx, cy;
    private float   density;
    private boolean ready = false;
    private Runnable onEndListener;
    private ValueAnimator burstAnim, holdAnim;

    public MistBackgroundView(Context c) { super(c); init(); }
    public MistBackgroundView(Context c, AttributeSet a) { super(c, a); init(); }
    public MistBackgroundView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        bigPaint.setStyle(Paint.Style.FILL);
        bigPaint.setMaskFilter(new BlurMaskFilter(dp(38), BlurMaskFilter.Blur.NORMAL));

        smPaint.setStyle(Paint.Style.FILL);
        smPaint.setMaskFilter(new BlurMaskFilter(dp(18), BlurMaskFilter.Blur.NORMAL));

        blPaint.setStyle(Paint.Style.FILL);
        blPaint.setMaskFilter(new BlurMaskFilter(dp(50), BlurMaskFilter.Blur.NORMAL));

        boPaint.setStyle(Paint.Style.FILL);
        boPaint.setMaskFilter(new BlurMaskFilter(dp(65), BlurMaskFilter.Blur.NORMAL));

        flashPaint.setStyle(Paint.Style.FILL);
        flashPaint.setMaskFilter(new BlurMaskFilter(dp(30), BlurMaskFilter.Blur.NORMAL));

        solidBlack.setStyle(Paint.Style.FILL);
        solidBlack.setColor(Color.BLACK);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w * 0.5f;
        cy = h * 0.48f;
        build(w, h);
    }

    private void build(int w, int h) {
        Random rng     = new Random(55);
        float diagonal = (float) Math.hypot(w, h);

        // ── Big color puffs ───────────────────────────────────────────────
        for (int i = 0; i < BIG_COUNT; i++) {
            // Assign a color sector — groups of puffs share a color so the
            // cloud has distinct vivid zones like a real color firework
            int sector      = i % COLORS.length;
            float sectorAng = (float)(Math.PI * 2.0 * sector / COLORS.length);
            // Puffs cluster near their color's sector angle
            bigAngle[i]  = sectorAng + (rng.nextFloat() - 0.5f) * 0.85f;
            bigSpeed[i]  = 0.45f + rng.nextFloat() * 0.55f;
            bigR[i]      = dp(70) + rng.nextFloat() * dp(65);
            bigGrow[i]   = dp(55) + rng.nextFloat() * dp(65);
            // Slightly oval so cloud looks more organic
            bigAspW[i]   = 0.75f + rng.nextFloat() * 0.50f;
            bigAspH[i]   = 0.65f + rng.nextFloat() * 0.35f;
            bigRot[i]    = bigAngle[i] + (rng.nextFloat() - 0.5f) * 0.9f;
            bigPhase[i]  = rng.nextFloat() * 6.28f;
            bigTurbA[i]  = dp(16) + rng.nextFloat() * dp(24);
            bigTurbF[i]  = 1.2f   + rng.nextFloat() * 1.8f;
            bigAlpha[i]  = 0.80f  + rng.nextFloat() * 0.20f;
            bigDelay[i]  = rng.nextFloat() * 0.07f;
            bigColor[i]  = COLORS[sector];
        }

        // ── Small color puffs ─────────────────────────────────────────────
        for (int i = 0; i < SMALL_COUNT; i++) {
            int sector     = rng.nextInt(COLORS.length);
            smAngle[i]  = (float)(Math.PI * 2.0 * sector / COLORS.length)
                    + (rng.nextFloat() - 0.5f) * 1.2f;
            smSpeed[i]  = 0.35f + rng.nextFloat() * 0.65f;
            smR[i]      = dp(30) + rng.nextFloat() * dp(40);
            smGrow[i]   = dp(25) + rng.nextFloat() * dp(35);
            smPhase[i]  = rng.nextFloat() * 6.28f;
            smTurbA[i]  = dp(10) + rng.nextFloat() * dp(18);
            smTurbF[i]  = 1.5f   + rng.nextFloat() * 2.5f;
            smAlpha[i]  = 0.65f  + rng.nextFloat() * 0.35f;
            smDelay[i]  = rng.nextFloat() * 0.09f;
            smColor[i]  = COLORS[sector];
        }

        // ── Black smoke puffs (same spread as color, but delayed) ─────────
        for (int i = 0; i < BLACK_COUNT; i++) {
            blAngle[i]  = rng.nextFloat() * (float)(Math.PI * 2.0);
            blSpeed[i]  = 0.40f + rng.nextFloat() * 0.60f;
            blR[i]      = dp(75) + rng.nextFloat() * dp(85);
            blGrow[i]   = dp(70) + rng.nextFloat() * dp(90);
            blPhase[i]  = rng.nextFloat() * 6.28f;
            blTurbA[i]  = dp(20) + rng.nextFloat() * dp(30);
            blTurbF[i]  = 1.0f   + rng.nextFloat() * 2.0f;
            blAlpha[i]  = 0.75f  + rng.nextFloat() * 0.25f;
            // Stagger: first black puff at t=0.28, last at t=0.55
            blDelay[i]  = 0.28f  + rng.nextFloat() * 0.27f;
        }

        // ── Central bloom: huge black blobs that grow from center ─────────
        for (int i = 0; i < BLOOM_COUNT; i++) {
            boR[i]     = dp(90)  + rng.nextFloat() * dp(80);
            boOffX[i]  = (rng.nextFloat() - 0.5f) * dp(60);
            boOffY[i]  = (rng.nextFloat() - 0.5f) * dp(50);
            boDelay[i] = 0.32f   + rng.nextFloat() * 0.20f;
            boAlpha[i] = 0.80f   + rng.nextFloat() * 0.20f;
        }

        ready = true;
    }

    @Override
    protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); }

    @Override
    protected void onDetachedFromWindow() {
        if (burstAnim != null) burstAnim.cancel();
        if (holdAnim  != null) holdAnim.cancel();
        super.onDetachedFromWindow();
    }

    public void start() {
        if (getWidth() == 0) { post(this::start); return; }
        if (!ready) build(getWidth(), getHeight());
        if (burstAnim != null) burstAnim.cancel();
        if (holdAnim  != null) holdAnim.cancel();
        t = 0f;

        burstAnim = ValueAnimator.ofFloat(0f, 1f);
        burstAnim.setDuration(BURST_MS);
        burstAnim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        burstAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                t = 1f; invalidate();
                holdAnim = ValueAnimator.ofFloat(0f, 1f);
                holdAnim.setDuration(HOLD_MS);
                holdAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        if (onEndListener != null) onEndListener.run();
                    }
                });
                holdAnim.start();
            }
        });
        burstAnim.start();
    }

    public void setOnAnimationEndListener(Runnable l) { onEndListener = l; }

    // ── Draw ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;

        float w        = getWidth();
        float h        = getHeight();
        float diagonal = (float) Math.hypot(w, h);
        float maxDist  = diagonal * 0.52f;

        // ════════════════════════════════════════════════════════════════
        //  COLOR CLOUD — drawn first (background of the scene)
        // ════════════════════════════════════════════════════════════════

        // Fade color cloud out as black takes over (0.45 → 0.88)
        float colorFade = envelope(t, 0f, 0.12f, 0.45f, 0.88f);

        if (colorFade > 0.01f) {
            // Big puffs
            for (int i = 0; i < BIG_COUNT; i++) {
                float lt = localT(t, bigDelay[i]);
                if (lt <= 0f) continue;
                float e  = easeOutQuart(lt);

                float[] p = puffPos(maxDist, bigSpeed[i], bigAngle[i],
                        bigPhase[i], bigTurbA[i], bigTurbF[i], e);

                float rW = (bigR[i] + bigGrow[i] * e) * bigAspW[i];
                float rH = (bigR[i] + bigGrow[i] * e) * bigAspH[i];

                bigPaint.setColor(colorA(bigColor[i], bigAlpha[i] * colorFade));
                drawOval(canvas, p[0], p[1], rW, rH, bigRot[i]);
                bigPaint.setColor(0); // reset
            }

            // Small puffs
            for (int i = 0; i < SMALL_COUNT; i++) {
                float lt = localT(t, smDelay[i]);
                if (lt <= 0f) continue;
                float e  = easeOutCubic(lt);

                float[] p = puffPos(maxDist, smSpeed[i], smAngle[i],
                        smPhase[i], smTurbA[i], smTurbF[i], e);

                float r  = (smR[i] + smGrow[i] * e);
                smPaint.setColor(colorA(smColor[i], smAlpha[i] * colorFade));
                canvas.drawCircle(p[0], p[1], r, smPaint);
            }
        }

        // ════════════════════════════════════════════════════════════════
        //  FLASH — white center burst at t=0 (on top of color, under black)
        // ════════════════════════════════════════════════════════════════
        if (t < 0.20f) {
            float fa = t < 0.05f ? (t / 0.05f) : (1f - (t - 0.05f) / 0.15f);
            fa = clamp01(fa);
            if (fa > 0.01f) {
                float fR = dp(10) + dp(200) * easeOutQuart(Math.min(1f, t / 0.08f));
                // White radial gradient — just the core flash
                RadialGradient rg = new RadialGradient(cx, cy, fR,
                        new int[]{
                                withAlpha(0xFFFFFFFF, fa * 0.95f),
                                withAlpha(0xFFFFFFFF, fa * 0.30f),
                                withAlpha(0xFFFFFFFF, 0f)
                        },
                        new float[]{ 0f, 0.35f, 1f },
                        Shader.TileMode.CLAMP);
                flashPaint.setShader(rg);
                canvas.drawCircle(cx, cy, fR, flashPaint);
                flashPaint.setShader(null);
            }
        }

        // ════════════════════════════════════════════════════════════════
        //  BLACK SMOKE — grows over the color cloud slowly
        // ════════════════════════════════════════════════════════════════

        // Central bloom (rises from center first)
        for (int i = 0; i < BLOOM_COUNT; i++) {
            if (t < boDelay[i]) continue;
            float lt = clamp01((t - boDelay[i]) / (1f - boDelay[i]));
            float e  = easeOutCubic(lt);
            float r  = boR[i] * (0.2f + 0.8f * e) + dp(40) * e;
            float a  = boAlpha[i] * clamp01(lt * 2.5f); // ramp up fast
            boPaint.setColor(blackA(a));
            canvas.drawCircle(cx + boOffX[i], cy + boOffY[i], r, boPaint);
        }

        // Expanding black puffs that spread outward following color paths
        for (int i = 0; i < BLACK_COUNT; i++) {
            if (t < blDelay[i]) continue;
            float lt = clamp01((t - blDelay[i]) / (1f - blDelay[i]));
            float e  = easeOutCubic(lt);

            float[] p = puffPos(maxDist, blSpeed[i], blAngle[i],
                    blPhase[i], blTurbA[i], blTurbF[i], e);

            float r  = (blR[i] + blGrow[i] * e);
            float a  = blAlpha[i] * clamp01(lt * 2.0f);
            blPaint.setColor(blackA(a));
            canvas.drawCircle(p[0], p[1], r, blPaint);
        }

        // ── Full black guarantee ──────────────────────────────────────────
        if (t > 0.85f) {
            solidBlack.setAlpha((int)(clamp01((t - 0.85f) / 0.15f) * 255));
            canvas.drawRect(0, 0, w, h, solidBlack);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private float[] puffPos(float maxDist, float speed, float angle,
                            float phase, float turbA, float turbF, float e) {
        float dist = maxDist * speed * e;
        float bx   = cx + (float)Math.cos(angle) * dist;
        float by   = cy + (float)Math.sin(angle) * dist;
        // Perpendicular turbulence — makes cloud edge organic
        float perp   = angle + 1.5708f;
        float wobble = (float)Math.sin(phase + e * turbF * 3.14159f) * turbA * e;
        return new float[]{
                bx + (float)Math.cos(perp) * wobble,
                by + (float)Math.sin(perp) * wobble
        };
    }

    private void drawOval(Canvas canvas, float x, float y,
                          float rW, float rH, float angle) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate((float) Math.toDegrees(angle));
        canvas.drawOval(-rW, -rH, rW, rH, bigPaint);
        canvas.restore();
    }

    /** ARGB with the color's RGB but our alpha */
    private static int colorA(int color, float alpha) {
        return (color & 0x00FFFFFF) | ((int)(clamp01(alpha) * 255) << 24);
    }

    /** Pure black with alpha */
    private static int blackA(float alpha) {
        return ((int)(clamp01(alpha) * 255) << 24);
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | ((int)(clamp01(alpha) * 255) << 24);
    }

    /** Smooth ramp-in / hold / ramp-out envelope */
    private static float envelope(float t, float start, float fullOn,
                                  float fadeOut, float end) {
        if (t <= start || t >= end) return 0f;
        float a = 1f;
        if (t < fullOn)  a = (t - start)   / (fullOn - start);
        if (t > fadeOut) a = 1f - (t - fadeOut) / (end - fadeOut);
        return clamp01(a);
    }

    private static float localT(float t, float delay) {
        return clamp01((t - delay) / (1f - delay));
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private static float easeOutCubic(float t) {
        float i = 1f - t; return 1f - i * i * i;
    }
    private static float easeOutQuart(float t) {
        float i = 1f - t; return 1f - i * i * i * i;
    }

    private float dp(float v) { return v * density; }
}