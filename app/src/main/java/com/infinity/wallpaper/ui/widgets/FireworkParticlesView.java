package com.infinity.wallpaper.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lightweight splash particle renderer.
 *
 * Modes:
 * - blast(): spark explosion with trails + shockwave (kept for reuse elsewhere).
 * - cloudBurst(): non-uniform dust/gas expansion that fills to black.
 */
public class FireworkParticlesView extends View {

    private static final int DEFAULT_PARTICLES = 70;

    private final Random random = new Random();

    // Spark mode paints
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Cloud mode paints
    private final Paint smokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Spark mode lists
    private final List<Particle> sparks = new ArrayList<>();

    // Cloud mode lists
    private final List<Smoke> smoke = new ArrayList<>();
    private final List<Dust> dust = new ArrayList<>();

    private float progress = 0f;
    private ValueAnimator animator;

    private float originX;
    private float originY;

    private Mode mode = Mode.BLAST;

    private enum Mode {
        BLAST,
        CLOUD
    }

    public FireworkParticlesView(Context context) {
        super(context);
        init();
    }

    public FireworkParticlesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FireworkParticlesView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Spark look
        sparkPaint.setStyle(Paint.Style.FILL);

        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeJoin(Paint.Join.ROUND);

        // Cloud look
        smokePaint.setStyle(Paint.Style.FILL);
        cloudPaint.setStyle(Paint.Style.FILL);

        dustPaint.setStyle(Paint.Style.FILL);
        dustPaint.setColor(0x22FFFFFF);

        setAlpha(0f);
        setWillNotDraw(false);
    }

    /** Spark explosion with trails + subtle shockwave. */
    public void blast(long durationMs) {
        mode = Mode.BLAST;
        startSpark(DEFAULT_PARTICLES, durationMs, /*secondary=*/true);
    }

    /**
     * Cloud-burst: non-uniform dust/gas spread (no uniform spokes), then a dark cloud fills to black.
     */
    public void cloudBurst(long durationMs) {
        mode = Mode.CLOUD;
        startCloud(durationMs);
    }

    /** Emits a new spark burst and starts animating it. */
    public void burst(int particleCount, long durationMs) {
        mode = Mode.BLAST;
        startSpark(particleCount, durationMs, /*secondary=*/false);
    }

    private void startSpark(int particleCount, long durationMs, boolean secondaryBurst) {
        if (getWidth() == 0 || getHeight() == 0) {
            post(() -> startSpark(particleCount, durationMs, secondaryBurst));
            return;
        }

        sparks.clear();
        smoke.clear();
        dust.clear();

        originX = getWidth() * 0.5f;
        originY = getHeight() * 0.42f;

        int count = particleCount <= 0 ? DEFAULT_PARTICLES : particleCount;

        for (int i = 0; i < count; i++) {
            float angle = (float) (random.nextFloat() * Math.PI * 2.0);

            float base = dp(120) + random.nextFloat() * dp(260);
            base *= (random.nextFloat() < 0.18f) ? 0.55f : 1.0f;

            float r = dp(1.3f) + random.nextFloat() * dp(2.6f);

            int color = randomSparkColor();

            float gravity = dp(36) + random.nextFloat() * dp(64);

            float burn = 0.35f + random.nextFloat() * 0.35f;

            sparks.add(new Particle(originX, originY, angle, base, r, color, gravity, burn));
        }

        if (secondaryBurst) {
            for (int i = 0; i < 16; i++) {
                float angle = (float) (random.nextFloat() * Math.PI * 2.0);
                float base = dp(60) + random.nextFloat() * dp(140);
                float r = dp(0.9f) + random.nextFloat() * dp(1.8f);
                int color = 0xFFFFFFFF;
                float gravity = dp(18) + random.nextFloat() * dp(42);
                float burn = 0.15f + random.nextFloat() * 0.25f;
                sparks.add(new Particle(originX, originY, angle, base, r, color, gravity, burn));
            }
        }

        startAnimator(Math.max(420L, durationMs), /*fadeStart=*/0.60f);
    }

    private void startCloud(long durationMs) {
        if (getWidth() == 0 || getHeight() == 0) {
            post(() -> startCloud(durationMs));
            return;
        }

        sparks.clear();
        smoke.clear();
        dust.clear();

        originX = getWidth() * 0.5f;
        originY = getHeight() * 0.45f;

        // Big irregular blobs (gas) that expand + drift with different speeds.
        // Purpose: "cloud burst" look instead of radial fireworks.
        int blobs = 28;
        for (int i = 0; i < blobs; i++) {
            float a = (float) (random.nextFloat() * Math.PI * 2.0);
            float d = dp(10) + random.nextFloat() * dp(46);
            float sx = originX + (float) Math.cos(a) * d;
            float sy = originY + (float) Math.sin(a) * d;

            float startR = dp(22) + random.nextFloat() * dp(28);
            float endR = startR + dp(160) + random.nextFloat() * dp(220);

            // Turbulent drift components (non-uniform)
            float driftX = (random.nextFloat() - 0.5f) * dp(320);
            float driftY = (random.nextFloat() - 0.5f) * dp(220);

            // Swirl parameters (adds non-uniform eddies)
            float swirlPhase = random.nextFloat() * 10f;
            float swirlAmp = dp(10) + random.nextFloat() * dp(26);
            float swirlSpeed = 1.6f + random.nextFloat() * 2.2f;

            int alpha = 36 + random.nextInt(54);
            int c = (alpha << 24) | 0x00101010; // near-black gas

            smoke.add(new Smoke(sx, sy, driftX, driftY, startR, endR, c, swirlPhase, swirlAmp, swirlSpeed));
        }

        // Fine dust grain field (tiny particles) that drifts outward but not uniformly.
        int grains = 230;
        for (int i = 0; i < grains; i++) {
            float a = (float) (random.nextFloat() * Math.PI * 2.0);
            float d = random.nextFloat() * dp(80);
            float x = originX + (float) Math.cos(a) * d;
            float y = originY + (float) Math.sin(a) * d;

            float vx = (random.nextFloat() - 0.5f) * dp(480);
            float vy = (random.nextFloat() - 0.5f) * dp(380);

            // Bias slightly downward for realism
            vy += dp(160);

            float r = dp(0.6f) + random.nextFloat() * dp(1.4f);
            int alpha = 18 + random.nextInt(36);
            int c = (alpha << 24) | 0x00FFFFFF;

            float jitterPhase = random.nextFloat() * 10f;
            float jitterAmp = dp(2f) + random.nextFloat() * dp(4f);
            float jitterSpeed = 2.0f + random.nextFloat() * 2.5f;

            dust.add(new Dust(x, y, vx, vy, r, c, jitterPhase, jitterAmp, jitterSpeed));
        }

        // Longer hold; the "blackening" is part of the cloud, not a simple fade.
        startAnimator(Math.max(650L, durationMs), /*fadeStart=*/0.82f);
    }

    private void startAnimator(long durationMs, float fadeStart) {
        if (animator != null) {
            animator.cancel();
        }

        setAlpha(1f);
        progress = 0f;

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            float fade = 1f - clamp01((progress - fadeStart) / (1f - fadeStart));
            setAlpha(fade);
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (sparks.isEmpty() && smoke.isEmpty() && dust.isEmpty()) return;

        float t = progress;

        if (mode == Mode.CLOUD) {
            // Expanding dark cloud: not a perfect ring, but a strong, soft blackening.
            float cloudT = clamp01((t - 0.08f) / 0.86f);
            if (cloudT > 0f) {
                float maxR = (float) Math.hypot(getWidth(), getHeight()) * 1.05f;
                float r = maxR * cloudT;

                int alpha = (int) (235 * cloudT);
                cloudPaint.setColor((alpha << 24) | 0x00000000);
                canvas.drawCircle(originX, originY, r, cloudPaint);
            }

            // Smoke blobs
            float sT = clamp01((t - 0.01f) / 0.99f);
            float e = easeOutCubic(sT);
            for (Smoke s : smoke) {
                float rr = lerp(s.startR, s.endR, e);

                float swirlX = (float) Math.sin(s.swirlPhase + sT * s.swirlSpeed) * s.swirlAmp;
                float swirlY = (float) Math.cos(s.swirlPhase + sT * s.swirlSpeed) * (s.swirlAmp * 0.7f);

                float x = s.x + s.driftX * sT + swirlX;
                float y = s.y + s.driftY * sT + swirlY;

                int a = (int) (((s.color >>> 24) & 0xFF) * (1f - clamp01((sT - 0.55f) / 0.45f)));
                smokePaint.setColor((a << 24) | (s.color & 0x00FFFFFF));
                canvas.drawCircle(x, y, rr, smokePaint);
            }

            // Dust grains
            for (Dust d : dust) {
                float jx = (float) Math.sin(d.jitterPhase + t * d.jitterSpeed) * d.jitterAmp;
                float jy = (float) Math.cos(d.jitterPhase + t * d.jitterSpeed) * (d.jitterAmp * 0.9f);

                float x = d.x + d.vx * t + jx;
                float y = d.y + d.vy * t + jy;

                int a = (int) (((d.color >>> 24) & 0xFF) * (1f - clamp01((t - 0.62f) / 0.38f)));
                dustPaint.setColor((a << 24) | (d.color & 0x00FFFFFF));
                canvas.drawCircle(x, y, d.r, dustPaint);
            }

            return;
        }

        // Spark mode (kept)
        float ringT = clamp01((t - 0.06f) / 0.55f);
        if (ringT > 0f && ringT < 1f) {
            float maxR = (float) Math.hypot(getWidth(), getHeight()) * 0.62f;
            float r = maxR * ringT;

            int alpha = (int) (110 * (1f - ringT));
            ringPaint.setColor((alpha << 24) | 0x00FFFFFF);
            ringPaint.setStrokeWidth(dp(2.2f) + dp(2f) * (1f - ringT));
            canvas.drawCircle(originX, originY, r, ringPaint);
        }

        for (Particle p : sparks) {
            float travel = easeOutCubic(t);

            float dx = (float) Math.cos(p.angle) * p.distance * travel;
            float dy = (float) Math.sin(p.angle) * p.distance * travel;
            dy += p.gravity * t * t;

            float tail = dp(22) + dp(46) * (1f - t);
            float tx = (float) Math.cos(p.angle) * Math.max(0f, (p.distance * travel - tail));
            float ty = (float) Math.sin(p.angle) * Math.max(0f, (p.distance * travel - tail));
            ty += p.gravity * t * t;

            int trailAlpha = (int) (110 * (1f - t));
            trailPaint.setColor((trailAlpha << 24) | (p.color & 0x00FFFFFF));
            trailPaint.setStrokeWidth(Math.max(dp(1.2f), p.radius));
            canvas.drawLine(p.cx + tx, p.cy + ty, p.cx + dx, p.cy + dy, trailPaint);

            float bright = 1f - clamp01((t - p.burn) / (1f - p.burn));
            int headAlpha = (int) (255 * (0.45f + 0.55f * bright) * (1f - clamp01((t - 0.70f) / 0.30f)));
            sparkPaint.setColor((headAlpha << 24) | (p.color & 0x00FFFFFF));

            float rr = p.radius * (1.15f - 0.25f * t);
            canvas.drawCircle(p.cx + dx, p.cy + dy, rr, sparkPaint);
        }
    }

    private int randomSparkColor() {
        int[] colors = new int[] {
                0xFFFFF1C1,
                0xFFFFD166,
                0xFFFF9F1C,
                0xFFFF5C8A,
                0xFFFB3640
        };
        return colors[random.nextInt(colors.length)];
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

    private static final class Particle {
        final float cx;
        final float cy;
        final float angle;
        final float distance;
        final float radius;
        final int color;
        final float gravity;
        final float burn;

        Particle(float cx, float cy, float angle, float distance, float radius, int color, float gravity, float burn) {
            this.cx = cx;
            this.cy = cy;
            this.angle = angle;
            this.distance = distance;
            this.radius = radius;
            this.color = color;
            this.gravity = gravity;
            this.burn = burn;
        }
    }

    private static final class Smoke {
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

        Smoke(float x,
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

    private static final class Dust {
        final float x;
        final float y;
        final float vx;
        final float vy;
        final float r;
        final int color;
        final float jitterPhase;
        final float jitterAmp;
        final float jitterSpeed;

        Dust(float x,
             float y,
             float vx,
             float vy,
             float r,
             int color,
             float jitterPhase,
             float jitterAmp,
             float jitterSpeed) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.r = r;
            this.color = color;
            this.jitterPhase = jitterPhase;
            this.jitterAmp = jitterAmp;
            this.jitterSpeed = jitterSpeed;
        }
    }
}
