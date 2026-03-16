package com.infinity.wallpaper.ui.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.gson.Gson;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.render.ThemeRenderer;
import com.infinity.wallpaper.util.DownloadWithProgress;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Bottom sheet that always shows all themes of a wallpaper as horizontal preview cards.
 * Each card uses the theme's own previewUrl (if stored) or falls back to compositing bg+mask+text.
 * User taps a card to apply that theme.
 */
public class ThemePickerSheet {

    private static final String TAG = "ThemePickerSheet";

    public interface OnThemeSelectedListener {
        void onSelected(String themeKey, String themeJson, WallpaperItem item);
    }

    /**
     * Always shows the sheet — even for a single theme — so user explicitly picks a style.
     */
    public static void show(@NonNull Context ctx,
                            @NonNull WallpaperItem item,
                            @NonNull OnThemeSelectedListener listener) {
        LinkedHashMap<String, String> themes = buildThemeMap(item);

        // If no themes at all, call through immediately
        if (themes.isEmpty()) {
            listener.onSelected("theme1", "{}", item);
            return;
        }

        // Use default Material BottomSheet styling from the app theme
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        View sheetView = LayoutInflater.from(ctx).inflate(R.layout.sheet_theme_picker, null);
        sheet.setContentView(sheetView);

        TextView tvTitle = sheetView.findViewById(R.id.sheet_theme_title);
        tvTitle.setText(item.name != null ? item.name : "Choose Style");

        RecyclerView rv = sheetView.findViewById(R.id.sheet_theme_recycler);
        rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));

        List<String> keys = new ArrayList<>(themes.keySet());
        ThemeCardAdapter adapter = new ThemeCardAdapter(ctx, keys, themes, item, themeKey -> {
            sheet.dismiss();
            listener.onSelected(themeKey, themes.get(themeKey), item);
        });
        rv.setAdapter(adapter);

        sheet.show();
    }

    // ── Build ordered theme map ─────────────────────────────────────────────

    public static LinkedHashMap<String, String> buildThemeMap(WallpaperItem item) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (item.themes == null) return map;
        List<String> keys = new ArrayList<>(item.themes.keySet());
        keys.sort((a, b) -> Integer.compare(extractNum(a), extractNum(b)));
        Gson gson = new Gson();
        for (String key : keys) {
            Object val = item.themes.get(key);
            if (val == null) continue;
            String json = val instanceof String ? (String) val : gson.toJson(val);
            map.put(key, json);
        }
        return map;
    }

    static int extractNum(String key) {
        try { return Integer.parseInt(key.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 999; }
    }

    /**
     * Extract previewUrl from a theme JSON if stored, e.g. theme.previewUrl or theme.time.previewUrl
     */
    public static String getThemePreviewUrl(String themeJson) {
        try {
            JSONObject o = new JSONObject(themeJson);
            // Top-level previewUrl in the theme object
            String url = o.optString("previewUrl", "");
            if (!url.isEmpty()) return url;
            // Or inside "time" sub-object
            JSONObject time = o.optJSONObject("time");
            if (time != null) {
                url = time.optString("previewUrl", "");
                if (!url.isEmpty()) return url;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private static class ThemeCardAdapter extends RecyclerView.Adapter<ThemeCardAdapter.VH> {

        interface OnPick { void pick(String themeKey); }

        private final Context ctx;
        private final List<String> keys;
        private final Map<String, String> themes;
        private final WallpaperItem item;
        private final OnPick onPick;

        ThemeCardAdapter(Context ctx, List<String> keys, Map<String, String> themes,
                         WallpaperItem item, OnPick onPick) {
            this.ctx = ctx; this.keys = keys; this.themes = themes;
            this.item = item; this.onPick = onPick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_theme_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String key = keys.get(pos);
            String themeJson = themes.get(key);
            int num = extractNum(key);
            h.tvLabel.setText("Style " + num);
            h.img.setImageDrawable(null);
            h.img.setBackgroundColor(0xFF222831);

            // 1. Try theme-level previewUrl first (fast, no rendering needed)
            String themePreviewUrl = getThemePreviewUrl(themeJson != null ? themeJson : "{}");
            if (themePreviewUrl != null && !themePreviewUrl.isEmpty()) {
                Glide.with(ctx).load(themePreviewUrl).centerCrop().into(h.img);
            } else {
                // 2. Fallback: composite-render bg + mask + text as thumbnail
                renderThumbAsync(h.img, themeJson);
            }

            h.itemView.setOnClickListener(v -> onPick.pick(key));
        }

        @Override public int getItemCount() { return keys.size(); }

        private void renderThumbAsync(ImageView target, String themeJson) {
            Handler main = new Handler(Looper.getMainLooper());
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    File cacheDir = new File(ctx.getCacheDir(), "wp_preview");
                    String sid = safeId(item.id);
                    File bgFile   = new File(cacheDir, sid + "_bg.png");
                    File maskFile = new File(cacheDir, sid + "_mask.png");

                    DownloadWithProgress dl = new DownloadWithProgress();
                    if (!bgFile.exists() && item.bgUrl != null && !item.bgUrl.isEmpty()) {
                        if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                            cacheDir.mkdirs();
                        dl.download(item.bgUrl, bgFile, null);
                    }
                    if (!maskFile.exists() && item.maskUrl != null && !item.maskUrl.isEmpty()) {
                        if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                            cacheDir.mkdirs();
                        dl.download(item.maskUrl, maskFile, null);
                    }

                    Bitmap bmp = composeThumb(ctx, bgFile, maskFile, themeJson, 180, 320);
                    if (bmp != null) main.post(() -> {
                        target.setImageBitmap(bmp);
                        target.setBackgroundColor(Color.TRANSPARENT);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Thumb render failed: " + e.getMessage());
                }
            });
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView img;
            TextView tvLabel;
            VH(View v) {
                super(v);
                img     = v.findViewById(R.id.theme_card_img);
                tvLabel = v.findViewById(R.id.theme_card_label);
            }
        }
    }

    // ── Composite helper ────────────────────────────────────────────────────

    static Bitmap composeThumb(Context ctx, File bgFile, File maskFile,
                               String themeJson, int w, int h) {
        try {
            Bitmap rawBg   = bgFile != null && bgFile.exists()
                    ? BitmapFactory.decodeFile(bgFile.getAbsolutePath()) : null;
            Bitmap rawMask = maskFile != null && maskFile.exists()
                    ? BitmapFactory.decodeFile(maskFile.getAbsolutePath()) : null;

            Bitmap bg   = rawBg   != null ? scaleCrop(rawBg,   w, h) : null;
            Bitmap mask = rawMask != null ? scaleCrop(rawMask, w, h) : null;
            if (rawBg   != null && rawBg   != bg)   rawBg.recycle();
            if (rawMask != null && rawMask != mask)  rawMask.recycle();

            float maskOpacity = 1.0f;
            try {
                JSONObject root = new JSONObject(themeJson != null ? themeJson : "{}");
                JSONObject time = root.optJSONObject("time");
                if (time != null) maskOpacity = (float) time.optDouble("maskOpacity", 1.0);
            } catch (Exception ignored) {}

            ThemeRenderer tr = new ThemeRenderer(ctx);
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(result);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            mp.setAlpha((int)(maskOpacity * 255));

            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else            c.drawColor(Color.BLACK);

            String depthMode = ThemeRenderer.getDepthMode(themeJson != null ? themeJson : "{}");
            if (!"none".equals(depthMode) && mask != null) {
                Bitmap back  = tr.renderBackLayer(themeJson,  w, h, true, 0, 0);
                Bitmap front = tr.renderFrontLayer(themeJson, w, h, true, 0, 0);
                if (back  != null) { c.drawBitmap(back,  0, 0, p); back.recycle(); }
                c.drawBitmap(mask, 0, 0, mp);
                if (front != null) { c.drawBitmap(front, 0, 0, p); front.recycle(); }
            } else {
                Bitmap textBmp = tr.renderThemeBitmap(themeJson, w, h, true, 0, 0);
                if (textBmp != null) { c.drawBitmap(textBmp, 0, 0, p); textBmp.recycle(); }
                if (mask != null)    c.drawBitmap(mask, 0, 0, mp);
            }
            if (bg   != null) bg.recycle();
            if (mask != null) mask.recycle();
            return result;
        } catch (Exception e) {
            Log.w(TAG, "composeThumb failed: " + e.getMessage());
            return null;
        }
    }

    private static Bitmap scaleCrop(Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int sw = Math.round(src.getWidth() * scale);
        int sh = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        int offX = (sw - w) / 2, offY = (sh - h) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, offX, offY, w, h);
        if (scaled != cropped) scaled.recycle();
        return cropped;
    }

    private static String safeId(String id) {
        if (id == null) return "wall";
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
