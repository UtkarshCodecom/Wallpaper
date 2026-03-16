package com.infinity.wallpaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import com.infinity.wallpaper.render.ThemeRenderer;
import com.infinity.wallpaper.util.SettingsManager;
import com.infinity.wallpaper.util.StudioManager;

import java.io.File;

/**
 * Simple, direct wallpaper service that loads local assets and renders them.
 * No GL overhead, no complex threading—just load bitmaps and draw on canvas.
 */
public class MyWallpaperServiceNew extends WallpaperService {

    /** Sent by UnlockReceiver (static, manifest-registered) to force an immediate redraw. */
    public static final String ACTION_REDRAW = "com.infinity.wallpaper.ACTION_REDRAW";

    @Override
    public Engine onCreateEngine() {
        return new MyEngine();
    }

    private class MyEngine extends Engine {

        private boolean redrawReceiverRegistered = false;

        // ── Cached bitmaps ──
        private Bitmap cachedBg   = null;
        private Bitmap cachedMask = null;
        private int    cachedW    = 0;
        private int    cachedH    = 0;
        private boolean cacheLoaded = false;

        // ── Receivers ──
        private final BroadcastReceiver redrawReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Log.d("WallpaperSvc", "redrawReceiver -> invalidate cache + startClockAnimation");
                invalidateCache();
                updateSecondsTicker();
                startClockAnimation();
            }
        };

        private final BroadcastReceiver timeTickReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    Log.d("WallpaperSvc", "Screen/unlock -> startClockAnimation");
                    unregisterTimeReceiverIfNeeded();
                    registerTimeReceiverIfNeeded();
                    updateSecondsTicker();
                    startClockAnimation();
                    return;
                }
                if (Intent.ACTION_TIME_TICK.equals(action)
                        || Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    Log.d("WallpaperSvc", "Time tick -> postDraw");
                    // Re-sync seconds ticker on each minute tick to stay aligned
                    if (isSecondsClockStyle()) {
                        stopSecondsTicker();
                        startSecondsTicker();
                    }
                    postDraw();
                }
            }
        };
        private boolean receiverRegistered = false;

        private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (SettingsManager.ACTION_SETTINGS_CHANGED.equals(intent.getAction())) {
                    invalidateCache();
                    registerTimeReceiverIfNeeded();
                    configureSensorRegistration();
                    updateSecondsTicker();
                    startClockAnimation();
                }
            }
        };

        // ── Sensors ──
        private SensorManager sensorManager;
        private Sensor rotationSensor;
        private boolean baselineCaptured = false;
        private float basePitch = 0f, baseRoll = 0f;

        // Auto-baseline reset: after user holds phone still for 500ms, reset baseline
        private long lastSignificantMoveMs = 0;
        private static final long BASELINE_RESET_DELAY_MS = 500;
        private float prevPitch = 0f, prevRoll = 0f;
        private static final float MOVE_THRESHOLD = 0.02f; // ~1 degree movement threshold

        private final SensorEventListener sensorListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
                try {
                    float[] rotMatrix = new float[9];
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);

                    // Remap coordinate system for portrait mode:
                    // X axis = screen's right edge  → maps to AXIS_X (roll = left/right)
                    // Y axis = screen's top edge     → maps to AXIS_Z (so pitch = forward/back)
                    // This corrects the gimbal-lock issue in default portrait orientation.
                    float[] remapped = new float[9];
                    SensorManager.remapCoordinateSystem(rotMatrix,
                            SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);

                    float[] ori = new float[3];
                    SensorManager.getOrientation(remapped, ori);
                    // After remap: ori[1]=pitch (forward/back), ori[2]=roll (left/right)
                    float rawPitch = ori[1];
                    float rawRoll  = ori[2];

                    // Check for significant movement to trigger auto-baseline reset
                    long now = System.currentTimeMillis();
                    float deltaPitch = Math.abs(rawPitch - prevPitch);
                    float deltaRoll = Math.abs(rawRoll - prevRoll);

                    if (deltaPitch > MOVE_THRESHOLD || deltaRoll > MOVE_THRESHOLD) {
                        lastSignificantMoveMs = now;
                    }
                    prevPitch = rawPitch;
                    prevRoll = rawRoll;

                    // Auto-reset baseline if phone has been still for BASELINE_RESET_DELAY_MS
                    if (baselineCaptured && (now - lastSignificantMoveMs) > BASELINE_RESET_DELAY_MS) {
                        // Smoothly transition baseline toward current position
                        float baseAlpha = 0.05f;
                        basePitch += (rawPitch - basePitch) * baseAlpha;
                        baseRoll += (rawRoll - baseRoll) * baseAlpha;
                    }

                    if (!baselineCaptured) {
                        basePitch = rawPitch;
                        baseRoll  = rawRoll;
                        baselineCaptured = true;
                        lastSignificantMoveMs = now;
                        return;
                    }

                    float clamp = 0.65f; // ~37 degrees max tilt range
                    float dp = Math.max(-clamp, Math.min(clamp, rawPitch - basePitch));
                    float dr = Math.max(-clamp, Math.min(clamp, rawRoll  - baseRoll));

                    // Sensitivity scaling: 0.2 to 0.8 based on user setting
                    float sens = 0.2f + (SettingsManager.getMotionSensitivity(MyWallpaperServiceNew.this) / 100f) * 0.6f;
                    float amount = 0.4f + (SettingsManager.getMotionAmount(MyWallpaperServiceNew.this) / 100f);

                    // 3D tilt: scale the raw values for visible effect
                    float tP3d = dp * sens * amount;
                    float tR3d = dr * sens * amount;

                    // Shift mode: pixel offsets
                    float maxPx = 50f * amount;
                    float tX =  (dr / clamp) * maxPx;
                    float tY = -(dp / clamp) * maxPx;

                    float alpha = 0.18f;  // smoother interpolation
                    smoothedPitch   += (tP3d - smoothedPitch)   * alpha;
                    smoothedRoll    += (tR3d - smoothedRoll)     * alpha;
                    smoothedOffsetX += (tX   - smoothedOffsetX)  * alpha;
                    smoothedOffsetY += (tY   - smoothedOffsetY)  * alpha;
                    lastPitch   = smoothedPitch;
                    lastRoll    = smoothedRoll;
                    lastOffsetX = smoothedOffsetX;
                    lastOffsetY = smoothedOffsetY;

                    if (now - lastSensorDrawMs > 16) {
                        lastSensorDrawMs = now;
                        postDraw();
                    }
                } catch (Exception ex) {
                    Log.w("WallpaperSvc", "Sensor: " + ex.getMessage());
                }
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };

        private boolean sensorRegistered = false;
        private volatile float lastOffsetX=0,lastOffsetY=0,lastPitch=0,lastRoll=0;
        private float smoothedOffsetX=0,smoothedOffsetY=0,smoothedPitch=0,smoothedRoll=0;
        private long lastSensorDrawMs = 0;

        // ── Clock animation ──
        private volatile float animPhase = 1.0f;
        private long animStartMs = 0;
        private static final int ANIM_FPS = 60;
        private android.os.Handler animHandler = null;   // main thread — schedules animation ticks
        private android.os.Handler drawHandler = null;   // background draw thread
        private android.os.HandlerThread drawThread = null;
        private Runnable animRunnable = null;

        // ── Per-second ticker (for HH:MM:SS / HH/MM/SS clock styles) ──
        private Runnable secondsRunnable = null;
        private boolean secondsTickerActive = false;

        /** Check if current theme uses a seconds clock style */
        private boolean isSecondsClockStyle() {
            try {
                String themeJson = StudioManager.getEffectiveThemeJson(MyWallpaperServiceNew.this);
                if (themeJson == null || themeJson.isEmpty()) return false;
                org.json.JSONObject root = new org.json.JSONObject(themeJson);
                org.json.JSONObject time = root.optJSONObject("time");
                if (time == null) return false;
                String style = time.optString("clockStyle", "");
                return "HH:MM:SS".equals(style) || "HH/MM/SS".equals(style);
            } catch (Exception e) { return false; }
        }

        private void startSecondsTicker() {
            if (secondsTickerActive || animHandler == null) return;
            secondsTickerActive = true;
            secondsRunnable = new Runnable() {
                @Override public void run() {
                    if (!secondsTickerActive) return;
                    postDraw();
                    // schedule next tick aligned to the next full second
                    long now = System.currentTimeMillis();
                    long delay = 1000 - (now % 1000);
                    if (delay < 50) delay += 1000; // avoid firing too soon
                    animHandler.postDelayed(this, delay);
                }
            };
            // fire first tick aligned to next second
            long now = System.currentTimeMillis();
            long delay = 1000 - (now % 1000);
            animHandler.postDelayed(secondsRunnable, delay);
        }

        private void stopSecondsTicker() {
            secondsTickerActive = false;
            if (animHandler != null && secondsRunnable != null) {
                animHandler.removeCallbacks(secondsRunnable);
                secondsRunnable = null;
            }
        }

        /** Start or stop seconds ticker based on current clock style */
        private void updateSecondsTicker() {
            if (isSecondsClockStyle()) {
                startSecondsTicker();
            } else {
                stopSecondsTicker();
            }
        }

        private void startClockAnimation() {
            if (!SettingsManager.isClockAnimationEnabled(MyWallpaperServiceNew.this)) {
                // Always draw immediately even when animation is disabled
                postDraw();
                return;
            }
            stopClockAnimation(); // cancel any running anim first
            animPhase   = 0f;
            animStartMs = System.currentTimeMillis();
            final long animDuration = SettingsManager.getAnimDurationMs(MyWallpaperServiceNew.this);
            // Create and assign animRunnable BEFORE posting so postDraw() sees it as active
            animRunnable = new Runnable() {
                @Override public void run() {
                    long elapsed = System.currentTimeMillis() - animStartMs;
                    float raw = Math.min(1f, (float) elapsed / animDuration);
                    animPhase = 1f - (float) Math.pow(1f - raw, 3); // ease-out cubic
                    postDraw();
                    if (elapsed < animDuration) {
                        animHandler.postDelayed(this, 1000 / ANIM_FPS);
                    } else {
                        animPhase    = 1.0f;
                        animRunnable = null; // mark done
                        postDraw();          // final frame at phase=1
                    }
                }
            };
            animHandler.post(animRunnable);
        }

        private void stopClockAnimation() {
            if (animHandler != null && animRunnable != null) {
                animHandler.removeCallbacks(animRunnable);
                animRunnable = null;
            }
        }

        /** Schedule a draw on the dedicated draw thread. */
        private void postDraw() {
            if (drawHandler == null) return;
            // During animation don't collapse — every frame must render
            // Outside animation collapse to avoid redundant redraws
            if (animRunnable == null) {
                drawHandler.removeCallbacksAndMessages(null);
            }
            drawHandler.post(this::drawFrame);
        }

        // ── Lifecycle ──
        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            // Main-thread handler for animation ticks
            animHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            // Dedicated background thread for all canvas drawing
            drawThread = new android.os.HandlerThread("WallpaperDraw");
            drawThread.start();
            drawHandler = new android.os.Handler(drawThread.getLooper());

            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

            registerBroadcast(settingsReceiver, new IntentFilter(SettingsManager.ACTION_SETTINGS_CHANGED));
            registerBroadcast(redrawReceiver,   new IntentFilter(ACTION_REDRAW));
            redrawReceiverRegistered = true;
        }

        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d("WallpaperSvc", "visible=" + visible);
            if (visible) {
                registerTimeReceiverIfNeeded();
                // Reset gyro baseline so 3D effect starts from current hold position
                baselineCaptured = false;
                smoothedPitch = smoothedRoll = smoothedOffsetX = smoothedOffsetY = 0f;
                lastPitch = lastRoll = lastOffsetX = lastOffsetY = 0f;
                // Reset auto-baseline tracking
                prevPitch = prevRoll = 0f;
                lastSignificantMoveMs = System.currentTimeMillis();
                configureSensorRegistration();
                updateSecondsTicker();
                startClockAnimation();
                // Always guarantee at least one draw even if animation/gyro are both off
                postDraw();
            } else {
                unregisterTimeReceiverIfNeeded();
                unregisterSensorIfNeeded();
                stopClockAnimation();
                stopSecondsTicker();
            }
        }

        @Override public void onDestroy() {
            super.onDestroy();
            stopClockAnimation();
            stopSecondsTicker();
            unregisterTimeReceiverIfNeeded();
            unregisterSensorIfNeeded();
            if (drawThread != null) { drawThread.quitSafely(); drawThread = null; drawHandler = null; }
            try { MyWallpaperServiceNew.this.unregisterReceiver(settingsReceiver); } catch (Exception ignored) {}
            if (redrawReceiverRegistered) {
                try { MyWallpaperServiceNew.this.unregisterReceiver(redrawReceiver); } catch (Exception ignored) {}
                redrawReceiverRegistered = false;
            }
            invalidateCache();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            invalidateCache();
            updateSecondsTicker();
            startClockAnimation();
        }

        private void registerBroadcast(BroadcastReceiver r, IntentFilter f) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    MyWallpaperServiceNew.this.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
                else
                    MyWallpaperServiceNew.this.registerReceiver(r, f);
            } catch (Exception ex) {
                try { MyWallpaperServiceNew.this.registerReceiver(r, f); } catch (Exception ignored) {}
            }
        }

        private void registerTimeReceiverIfNeeded() {
            if (receiverRegistered) return;
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerBroadcast(timeTickReceiver, f);
            receiverRegistered = true;
        }

        private void unregisterTimeReceiverIfNeeded() {
            if (!receiverRegistered) return;
            try { MyWallpaperServiceNew.this.unregisterReceiver(timeTickReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }

        private void configureSensorRegistration() {
            if (SettingsManager.isGyroEnabled(MyWallpaperServiceNew.this)) registerSensorIfNeeded();
            else unregisterSensorIfNeeded();
        }

        private void registerSensorIfNeeded() {
            if (sensorRegistered || sensorManager == null || rotationSensor == null) return;
            baselineCaptured = false;
            smoothedPitch = smoothedRoll = smoothedOffsetX = smoothedOffsetY = 0;
            try {
                sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
                sensorRegistered = true;
            } catch (Exception e) { Log.w("WallpaperSvc", "Sensor reg: " + e.getMessage()); }
        }

        private void unregisterSensorIfNeeded() {
            if (!sensorRegistered || sensorManager == null) return;
            try { sensorManager.unregisterListener(sensorListener); } catch (Exception ignored) {}
            sensorRegistered = false;
        }

        private boolean shouldShowTime() {
            try {
                KeyguardManager km = (KeyguardManager) MyWallpaperServiceNew.this.getSystemService(Context.KEYGUARD_SERVICE);
                boolean locked = (km != null && km.isKeyguardLocked());
                if (locked) {
                    return SettingsManager.isLockClockEnabled(MyWallpaperServiceNew.this);
                } else {
                    // Not locked = home screen; show clock if home clock is enabled
                    return SettingsManager.isHomeClockEnabled(MyWallpaperServiceNew.this);
                }
            } catch (Exception e) {
                return true; // fail-open: show clock
            }
        }

        /** Invalidate cached bg/mask so they'll be reloaded on next full draw */
        private void invalidateCache() {
            if (cachedBg   != null) { cachedBg.recycle();   cachedBg   = null; }
            if (cachedMask != null) { cachedMask.recycle(); cachedMask = null; }
            cacheLoaded = false;
            cachedW = cachedH = 0;
        }

        /**
         * Synchronous draw — called on the dedicated drawThread.
         * Never drops frames (no isDrawing guard), always renders the latest animPhase.
         */
        private void drawFrame() {
            Canvas canvas = null;
            try {
                Context ctx = MyWallpaperServiceNew.this;
                SurfaceHolder holder = getSurfaceHolder();

                Rect frame = holder.getSurfaceFrame();
                int tW = (frame != null && frame.width() > 0)  ? frame.width()  : 1080;
                int tH = (frame != null && frame.height() > 0) ? frame.height() : 1920;

                // Load/refresh cached bitmaps if needed
                if (!cacheLoaded || cachedW != tW || cachedH != tH) {
                    loadAndCacheBitmaps(ctx, tW, tH);
                }

                String themeJson = StudioManager.getEffectiveThemeJson(ctx);
                Log.d("WallpaperSvc", "drawFrame: themeJson=" + (!themeJson.isEmpty() ? "OK (len=" + themeJson.length() + ")" : "EMPTY"));
                boolean showTime = shouldShowTime();
                Log.d("WallpaperSvc", "drawFrame: showTime=" + showTime);
                ThemeRenderer tr = new ThemeRenderer(ctx);

                // Get mask opacity from theme JSON
                float maskOpacity = 1.0f;
                try {
                    org.json.JSONObject root = new org.json.JSONObject(themeJson);
                    org.json.JSONObject time = root.optJSONObject("time");
                    if (time != null) {
                        maskOpacity = (float) time.optDouble("maskOpacity", 1.0);
                    }
                } catch (Exception ignored) {}

                float rMX=0, rMY=0, rPitch=0, rRoll=0;
                int motionMode = -1;
                if (SettingsManager.isGyroEnabled(ctx)) {
                    motionMode = SettingsManager.getMotionMode(ctx);
                    if (motionMode == 1) { rMX = lastOffsetX; rMY = lastOffsetY; }
                    else                 { rPitch = lastPitch; rRoll = lastRoll;  }
                }

                int animStyle = SettingsManager.getClockAnimationStyle(ctx);

                // Depth-aware compositing: back → mask → front
                String depthMode = ThemeRenderer.getDepthMode(themeJson);
                Bitmap composed;
                if (!"none".equals(depthMode) && cachedMask != null) {
                    Bitmap backBmp  = tr.renderBackLayer(themeJson, tW, tH, showTime,
                            rMX, rMY, rPitch, rRoll, motionMode, animPhase, animStyle);
                    Bitmap frontBmp = tr.renderFrontLayer(themeJson, tW, tH, showTime,
                            rMX, rMY, rPitch, rRoll, motionMode, animPhase, animStyle);
                    composed = composeDepth(cachedBg, cachedMask, backBmp, frontBmp, tW, tH, maskOpacity);
                    if (backBmp  != null) backBmp.recycle();
                    if (frontBmp != null) frontBmp.recycle();
                } else {
                    Bitmap textBmp = tr.renderThemeBitmap(themeJson, tW, tH, showTime,
                            rMX, rMY, rPitch, rRoll, motionMode, animPhase, animStyle);
                    composed = composeFinal(cachedBg, cachedMask, textBmp, tW, tH, maskOpacity);
                    if (textBmp != null) textBmp.recycle();
                }

                try {
                    canvas = holder.lockCanvas();
                    if (canvas != null && composed != null) {
                        Paint p = new Paint();
                        p.setFilterBitmap(true);
                        canvas.drawBitmap(composed, 0, 0, p);
                    }
                } finally {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas);
                }

                if (composed != null) composed.recycle();

            } catch (Exception e) {
                Log.e("WallpaperSvc", "drawFrame: " + e.getMessage(), e);
                if (canvas != null) {
                    try { getSurfaceHolder().unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
                }
            }
        }

        private void loadAndCacheBitmaps(Context ctx, int tW, int tH) {
            try {
                File dir = new File(ctx.getFilesDir(), "wallpaper");
                File bgFile   = new File(dir, "bg.png");
                File maskFile = new File(dir, "mask.png");

                Bitmap rawBg   = bgFile.exists()   ? BitmapFactory.decodeFile(bgFile.getAbsolutePath())   : null;
                Bitmap rawMask = maskFile.exists()  ? BitmapFactory.decodeFile(maskFile.getAbsolutePath()) : null;

                if (cachedBg   != null) cachedBg.recycle();
                if (cachedMask != null) cachedMask.recycle();

                cachedBg   = (rawBg   != null) ? scaleAndCenterCrop(rawBg,   tW, tH) : null;
                cachedMask = (rawMask != null) ? scaleAndCenterCrop(rawMask, tW, tH) : null;
                cachedW = tW; cachedH = tH;
                cacheLoaded = true; // mark loaded even if files were absent

                if (rawBg   != null && rawBg   != cachedBg)   rawBg.recycle();
                if (rawMask != null && rawMask != cachedMask)  rawMask.recycle();

                Log.d("WallpaperSvc", "Cache loaded bg=" + (cachedBg != null) + " mask=" + (cachedMask != null));
            } catch (Exception e) {
                Log.e("WallpaperSvc", "loadAndCacheBitmaps: " + e.getMessage());
            }
        }

        private Bitmap composeFinal(Bitmap bg, Bitmap mask, Bitmap textBmp, int tW, int tH, float maskOpacity) {
            Bitmap base = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(base);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else c.drawColor(android.graphics.Color.BLACK);
            if (textBmp != null) c.drawBitmap(textBmp, 0, 0, p);
            if (mask    != null) {
                Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                maskPaint.setAlpha((int)(maskOpacity * 255));
                c.drawBitmap(mask, 0, 0, maskPaint);
            }
            return base;
        }

        /** Depth-aware compositing: bg → backText → mask → frontText */
        private Bitmap composeDepth(Bitmap bg, Bitmap mask, Bitmap backBmp, Bitmap frontBmp, int tW, int tH, float maskOpacity) {
            Bitmap base = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(base);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else c.drawColor(android.graphics.Color.BLACK);
            if (backBmp  != null) c.drawBitmap(backBmp,  0, 0, p);
            if (mask     != null) {
                Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                maskPaint.setAlpha((int)(maskOpacity * 255));
                c.drawBitmap(mask, 0, 0, maskPaint);
            }
            if (frontBmp != null) c.drawBitmap(frontBmp, 0, 0, p);
            return base;
        }

        private Bitmap scaleAndCenterCrop(Bitmap src, int tW, int tH) {
            if (src == null) return null;
            float scale = Math.max((float) tW / src.getWidth(), (float) tH / src.getHeight());
            int sW = Math.round(scale * src.getWidth()), sH = Math.round(scale * src.getHeight());
            Bitmap scaled  = Bitmap.createScaledBitmap(src, sW, sH, true);
            int x = Math.max(0, (sW - tW) / 2), y = Math.max(0, (sH - tH) / 2);
            Bitmap cropped = Bitmap.createBitmap(scaled, x, y, tW, tH);
            if (scaled != src && scaled != cropped) scaled.recycle();
            return cropped;
        }
    }
}

