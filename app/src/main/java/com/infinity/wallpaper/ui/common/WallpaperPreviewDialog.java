package com.infinity.wallpaper.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.render.ThemeRenderer;
import com.infinity.wallpaper.util.DownloadWithProgress;

import com.google.gson.Gson;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Full-screen wallpaper preview dialog.
 * Downloads bg+mask, composites with theme (time/date/depth), shows the real wallpaper preview.
 * "Set as Wallpaper" button applies it.
 */
public class WallpaperPreviewDialog {

    private static final String TAG = "WallpaperPreviewDialog";

    public interface OnApplyListener {
        void onApply(@NonNull WallpaperItem item);
    }

    public static void show(@NonNull Context ctx,
                            @NonNull WallpaperItem item,
                            @Nullable OnApplyListener onApply) {
        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_wallpaper_preview);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        ImageView imgPreview     = dialog.findViewById(R.id.wp_preview_image);
        ProgressBar pbLoading    = dialog.findViewById(R.id.wp_preview_loading);
        TextView tvName          = dialog.findViewById(R.id.wp_preview_name);
        TextView tvCategory      = dialog.findViewById(R.id.wp_preview_category);
        TextView btnClose        = dialog.findViewById(R.id.wp_preview_close);
        TextView btnSetWallpaper = dialog.findViewById(R.id.wp_preview_btn_set);
        TextView tvStatus        = dialog.findViewById(R.id.wp_preview_status);

        tvName.setText(item.name != null ? item.name : "");
        tvCategory.setText(item.category != null ? item.category : "");

        String previewUrl = (item.previewUrl != null && !item.previewUrl.isEmpty()) ? item.previewUrl : item.bgUrl;
        if (previewUrl != null && !previewUrl.isEmpty()) {
            Glide.with(ctx).load(previewUrl).centerCrop().into(imgPreview);
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());

        Handler mainHandler = new Handler(Looper.getMainLooper());

        pbLoading.setVisibility(View.VISIBLE);
        tvStatus.setText("Loading…");
        tvStatus.setVisibility(View.VISIBLE);
        btnSetWallpaper.setEnabled(false);
        btnSetWallpaper.setAlpha(0.5f);

        String bgUrl = (item.bgUrl != null && !item.bgUrl.isEmpty()) ? item.bgUrl : item.previewUrl;
        String maskUrl = item.maskUrl;
        Object themeObj = getFirstTheme(item);
        String themeJson = toJson(themeObj);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File cacheDir = new File(ctx.getCacheDir(), "wp_preview");
                if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                    cacheDir.mkdirs();

                File bgFile = new File(cacheDir, sanitize(item.id) + "_bg.png");
                File maskFile = new File(cacheDir, sanitize(item.id) + "_mask.png");

                DownloadWithProgress dl = new DownloadWithProgress();

                if (bgUrl != null && !bgUrl.isEmpty()) {
                    mainHandler.post(() -> tvStatus.setText("Downloading…"));
                    dl.download(bgUrl, bgFile, (bytes, total, done) -> {
                        if (total > 0) {
                            int pct = (int) (bytes * 100 / total);
                            mainHandler.post(() -> tvStatus.setText("Downloading… " + pct + "%"));
                        }
                    });
                }

                if (maskUrl != null && !maskUrl.isEmpty()) {
                    dl.download(maskUrl, maskFile, null);
                }

                mainHandler.post(() -> tvStatus.setText("Rendering…"));

                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int refW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
                int refH = (int) (refW * 20f / 9f);

                Bitmap composed = composePreview(ctx, bgFile, maskFile, themeJson, refW, refH);

                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.GONE);
                    btnSetWallpaper.setEnabled(true);
                    btnSetWallpaper.setAlpha(1f);

                    if (composed != null) {
                        imgPreview.setAlpha(0f);
                        imgPreview.setImageBitmap(composed);
                        imgPreview.animate().alpha(1f).setDuration(300)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Preview load failed: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    tvStatus.setText("Preview failed");
                    btnSetWallpaper.setEnabled(true);
                    btnSetWallpaper.setAlpha(1f);
                });
            }
        });

        btnSetWallpaper.setOnClickListener(v -> {
            dialog.dismiss();
            if (onApply == null) return;

            // If wallpaper has multiple themes, let user pick; otherwise apply directly.
            LinkedHashMap<String, String> themes = ThemePickerSheet.buildThemeMap(item);
            if (themes.size() <= 1) {
                onApply.onApply(item);
                return;
            }

            ThemePickerSheet.show(ctx, item, (themeKey, selectedThemeJson, selectedItem) -> {
                // Persist selected theme into item so the apply pipeline can read theme1Json from prefs.
                // We keep the callback type stable and rely on WallpaperApplier to read prefs.
                try {
                    // Store selected theme JSON into shared prefs for the service
                    ctx.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("theme_json", selectedThemeJson)
                            .apply();
                } catch (Exception ignored) {}
                onApply.onApply(selectedItem);
            });
        });

        dialog.show();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Bitmap composePreview(Context ctx, File bgFile, File maskFile,
                                         String themeJson, int w, int h) {
        try {
            Bitmap rawBg   = bgFile.exists()   ? BitmapFactory.decodeFile(bgFile.getAbsolutePath())   : null;
            Bitmap rawMask = maskFile != null && maskFile.exists()
                    ? BitmapFactory.decodeFile(maskFile.getAbsolutePath()) : null;

            Bitmap bg   = rawBg   != null ? scaleCrop(rawBg,   w, h) : null;
            Bitmap mask = rawMask != null ? scaleCrop(rawMask, w, h) : null;
            if (rawBg   != null && rawBg   != bg)   rawBg.recycle();
            if (rawMask != null && rawMask != mask)  rawMask.recycle();

            float maskOpacity = 1.0f;
            try {
                JSONObject root = new JSONObject(themeJson);
                JSONObject time = root.optJSONObject("time");
                if (time != null) maskOpacity = (float) time.optDouble("maskOpacity", 1.0);
            } catch (Exception ignored) {}

            ThemeRenderer tr = new ThemeRenderer(ctx);
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(result);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            maskPaint.setAlpha((int) (maskOpacity * 255));

            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else            c.drawColor(Color.BLACK);

            String depthMode = ThemeRenderer.getDepthMode(themeJson);
            if (!"none".equals(depthMode) && mask != null) {
                Bitmap back  = tr.renderBackLayer(themeJson,  w, h, true, 0, 0);
                Bitmap front = tr.renderFrontLayer(themeJson, w, h, true, 0, 0);
                if (back  != null) { c.drawBitmap(back,  0, 0, p); back.recycle(); }
                c.drawBitmap(mask, 0, 0, maskPaint);
                if (front != null) { c.drawBitmap(front, 0, 0, p); front.recycle(); }
            } else {
                Bitmap textBmp = tr.renderThemeBitmap(themeJson, w, h, true, 0, 0);
                if (textBmp != null) { c.drawBitmap(textBmp, 0, 0, p); textBmp.recycle(); }
                if (mask != null)    { c.drawBitmap(mask, 0, 0, maskPaint); }
            }

            if (bg   != null) bg.recycle();
            if (mask != null) mask.recycle();
            return result;
        } catch (Exception e) {
            Log.e(TAG, "composePreview failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static Bitmap scaleCrop(Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        float scaleX = (float) w / src.getWidth();
        float scaleY = (float) h / src.getHeight();
        float scale  = Math.max(scaleX, scaleY);
        int sw = Math.round(src.getWidth()  * scale);
        int sh = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        int offX = (sw - w) / 2, offY = (sh - h) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, offX, offY, w, h);
        if (scaled != cropped) scaled.recycle();
        return cropped;
    }

    @Nullable
    private static Object getFirstTheme(WallpaperItem item) {
        if (item.themes == null) return null;
        if (item.themes.containsKey("theme1")) return item.themes.get("theme1");
        for (Map.Entry<String, Object> e : item.themes.entrySet()) return e.getValue();
        return null;
    }

    private static String toJson(@Nullable Object obj) {
        if (obj == null) return "{}";
        if (obj instanceof String) return (String) obj;
        try { return new Gson().toJson(obj); } catch (Exception e) { return "{}"; }
    }

    static String sanitize(String id) {
        if (id == null) return "wall";
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
