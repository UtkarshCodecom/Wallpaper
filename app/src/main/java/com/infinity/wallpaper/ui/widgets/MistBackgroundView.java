package com.infinity.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Black-first fog burst splash effect.
 *
 * Starts on pure black, then a mid-screen fog/gas burst expands non-uniformly and
 * the whole screen darkens to black as if covered by the fog.
 */
public class MistBackgroundView extends View {

    private static final long DURATION_MS = 1250L;

    private final Random random = new Random();

    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Puff> puffs = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();

    private float t = 0f; // 0..1
    private ValueAnimator animator;

    private float cx;
    private float cy;

    private final int[] puffColors = new int[3];
    private final float[] puffStops = new float[] {0f, 0.70f, 1f};

    private final int[] coverColors = new int[3];
    private final float[] coverStops = new float[] {0f, 0.78f, 1f};

    private final int[] sparkColors = new int[] {
            0xFFFFF3CC, // warm white
            0xFFFFD166, // yellow
            0xFFFF9F1C, // orange
            0xFFFF5C8A  // pink
    };

    private float dp1;

    public MistBackgroundView(Context context) {
        super(context);
        init();
    }

    public MistBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MistBackgroundView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        dp1 = getResources().getDisplayMetrics().density;

        fogPaint.setStyle(Paint.Style.FILL);
        fogPaint.setDither(true);
        fogPaint.setMaskFilter(new BlurMaskFilter(dp(26), BlurMaskFilter.Blur.NORMAL));

        // sparks
        sparkPaint.setStyle(Paint.Style.FILL);
        sparkPaint.setDither(true);

        // spark trails
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setDither(true);

        // shockwave ring
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeJoin(Paint.Join.ROUND);

        coverPaint.setStyle(Paint.Style.FILL);

        // Needed for BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, fogPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w * 0.5f;
        cy = h * 0.45f;
        buildBurst();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    /** Starts the burst animation (can be called again for restart). */
    public void start() {
        if (getWidth() == 0 || getHeight() == 0) {
            post(this::start);
            return;
        }

        if (animator != null) {
            animator.cancel();
        }

        buildBurst();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void buildBurst() {
        puffs.clear();
        sparks.clear();

        // Smoke/mist puffs
        int count = 22;
        for (int i = 0; i < count; i++) {
            float ang = (float) (random.nextFloat() * Math.PI * 2.0);
            float dist = dp(6) + random.nextFloat() * dp(58);

            float ox = cx + (float) Math.cos(ang) * dist;
            float oy = cy + (float) Math.sin(ang) * dist;

            float baseR = dp(20) + random.nextFloat() * dp(34);
            float endR = baseR + dp(260) + random.nextFloat() * dp(240);

            float driftX = (random.nextFloat() - 0.5f) * dp(520);
            float driftY = (random.nextFloat() - 0.5f) * dp(360);

            float swirlPhase = random.nextFloat() * 10f;
            float swirlAmp = dp(10) + random.nextFloat() * dp(24);
            float swirlSpeed = 1.4f + random.nextFloat() * 2.2f;

            int alpha = 18 + random.nextInt(28);
            int rgb = 0x0018202C; // darker cool smoke
            int color = (alpha << 24) | rgb;

            puffs.add(new Puff(ox, oy, driftX, driftY, baseR, endR, color, swirlPhase, swirlAmp, swirlSpeed));
        }

        // Firecracker sparks
        int sparkCount = 70;
        for (int i = 0; i < sparkCount; i++) {
            float ang = (float) (random.nextFloat() * Math.PI * 2.0);
            float dist = dp(140) + random.nextFloat() * dp(420);
            float speedJitter = 0.65f + random.nextFloat() * 0.55f;

            float vx = (float) Math.cos(ang) * dist * speedJitter;
            float vy = (float) Math.sin(ang) * dist * speedJitter;

            // little gravity
            vy += dp(220) * (0.15f + random.nextFloat() * 0.35f);

            float r = dp(1.2f) + random.nextFloat() * dp(2.2f);

            int base = sparkColors[random.nextInt(sparkColors.length)];
            int a = 160 + random.nextInt(90);
            int c = (a << 24) | (base & 0x00FFFFFF);

            float born = random.nextFloat() * 0.10f; // micro stagger
            sparks.add(new Spark(vx, vy, r, c, born));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (puffs.isEmpty() && sparks.isEmpty()) return;

        float e = easeOutCubic(t);

        // Shockwave ring (very subtle)
        float ringT = clamp01((t - 0.04f) / 0.55f);
        if (ringT > 0f && ringT < 1f) {
            float maxR = (float) Math.hypot(getWidth(), getHeight()) * 0.28f;
            float rr = maxR * ringT;
            int a = (int) (120 * (1f - ringT));
            ringPaint.setColor((a << 24) | 0x00FFFFFF);
            ringPaint.setStrokeWidth((2.0f + 2.0f * (1f - ringT)) * dp1);
            canvas.drawCircle(cx, cy, rr, ringPaint);
        }

        // Sparks first (fast bright burst) + trails
        float sparkT = clamp01((t - 0.02f) / 0.60f);
        for (Spark s : sparks) {
            float localT = clamp01((sparkT - s.born) / (1f - s.born));
            float k = easeOutCubic(localT);

            float x = cx + s.vx * k;
            float y = cy + s.vy * k;

            // trail behind the spark
            float tail = (18f + 48f * (1f - localT)) * dp1;
            float len = (float) Math.hypot(s.vx, s.vy);
            float nx = len > 0.0001f ? (s.vx / len) : 0f;
            float ny = len > 0.0001f ? (s.vy / len) : 0f;

            float tx = x - nx * tail;
            float ty = y - ny * tail;

            float fade = 1f - clamp01((sparkT - 0.10f) / 0.90f);
            int a = (int) (((s.color >>> 24) & 0xFF) * fade);
            int rgb = (s.color & 0x00FFFFFF);

            trailPaint.setColor((Math.min(160, a) << 24) | rgb);
            trailPaint.setStrokeWidth(Math.max(1.2f * dp1, s.r));
            canvas.drawLine(tx, ty, x, y, trailPaint);

            sparkPaint.setColor((a << 24) | rgb);
            canvas.drawCircle(x, y, s.r, sparkPaint);
        }

        // Mist puffs (slower)
        for (Puff p : puffs) {
            float swirlX = (float) Math.sin(p.swirlPhase + t * p.swirlSpeed) * p.swirlAmp;
            float swirlY = (float) Math.cos(p.swirlPhase + t * p.swirlSpeed) * (p.swirlAmp * 0.75f);

            float x = p.x + p.driftX * t + swirlX;
            float y = p.y + p.driftY * t + swirlY;

            float r = lerp(p.startR, p.endR, e);

            float puffFade = 1f - clamp01((t - 0.72f) / 0.28f);
            int a = (int) (((p.color >>> 24) & 0xFF) * puffFade);
            int baseRgb = (p.color & 0x00FFFFFF);

            puffColors[0] = (a << 24) | baseRgb;
            puffColors[1] = ((int) (a * 0.55f) << 24) | baseRgb;
            puffColors[2] = 0x00000000;

            fogPaint.setShader(new RadialGradient(
                    x, y, r,
                    puffColors,
                    puffStops,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(x, y, r, fogPaint);
        }
        fogPaint.setShader(null);

        // Black spread from center (like smoke overtaking)
        float coverT = clamp01((t - 0.12f) / 0.88f);
        float coverE = easeOutCubic(coverT);

        float maxR = (float) Math.hypot(getWidth(), getHeight()) * 1.08f;
        float cr = maxR * coverE;

        int coverA = (int) (255 * clamp01(coverE * 1.05f));
        coverColors[0] = (coverA << 24);
        coverColors[1] = ((int) (coverA * 0.80f) << 24);
        coverColors[2] = 0xFF000000;

        coverPaint.setShader(new RadialGradient(
                cx, cy, Math.max(1f, cr),
                coverColors,
                coverStops,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, getWidth(), getHeight(), coverPaint);
        coverPaint.setShader(null);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class Puff {
        final float x;
        final float y;
        final float driftX;
        final float driftY;
        final float startR;
        final float endR;
        final int color;
        final float swirlPhase;
        final float swirlAmp;
        final float swirlSpeed;

        Puff(float x,
             float y,
             float driftX,
             float driftY,
             float startR,
             float endR,
             int color,
             float swirlPhase,
             float swirlAmp,
             float swirlSpeed) {
            this.x = x;
            this.y = y;
            this.driftX = driftX;
            this.driftY = driftY;
            this.startR = startR;
            this.endR = endR;
            this.color = color;
            this.swirlPhase = swirlPhase;
            this.swirlAmp = swirlAmp;
            this.swirlSpeed = swirlSpeed;
        }
    }

    private static final class Spark {
        final float vx;
        final float vy;
        final float r;
        final int color;
        final float born;

        Spark(float vx, float vy, float r, int color, float born) {
            this.vx = vx;
            this.vy = vy;
            this.r = r;
            this.color = color;
            this.born = born;
        }
    }
}
