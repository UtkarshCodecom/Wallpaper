package com.infinity.wallpaper.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.render.ThemeRenderer;
import com.infinity.wallpaper.ui.common.InlineColorPicker;
import com.infinity.wallpaper.util.SettingsManager;
import com.infinity.wallpaper.util.StudioManager;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

public class StudioFragment extends Fragment {

    public interface OnStudioResetListener {
        void onStudioReset();
    }

    static final java.util.concurrent.CopyOnWriteArrayList<OnStudioResetListener> resetListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void registerResetListener(OnStudioResetListener l) {
        if (l != null && !resetListeners.contains(l)) resetListeners.add(l);
    }

    static void unregisterResetListener(OnStudioResetListener l) {
        if (l != null) resetListeners.remove(l);
    }

    private void notifyStudioReset() {
        for (OnStudioResetListener l : resetListeners) {
            try { l.onStudioReset(); } catch (Exception ignored) {}
        }
    }

    private ImageView ivBg, ivText, ivMask;
    private ProgressBar pbPreview;
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingRefresh;

    // Per-second updater when seconds style is selected
    private final Handler secondHandler = new Handler(Looper.getMainLooper());
    private Runnable secondRunnable = null;
    private boolean secondsTickerRunning = false;

    static final String[] TAB_NAMES = {"Basics", "Typography", "Effects", "Transform", "Date", "Date Settings"};
    static final String[] FONTS = {
            "main.ttf", "main1.ttf", "main2.ttf", "main3.ttf",
            "Font-1.ttf",
            "fun1.ttf", "fun2.ttf", "fun3.ttf", "fun4.ttf", "fun5.ttf",
            "pine1.ttf", "pine2.ttf", "pine3.ttf", "pine4.ttf",
            "apple1.ttf", "apple2.ttf", "apple3.ttf", "apple4.ttf", "apple5.ttf"
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_studio, container, false);
        ivBg      = root.findViewById(R.id.studio_preview_bg);
        ivText    = root.findViewById(R.id.studio_preview_text);
        ivMask    = root.findViewById(R.id.studio_preview_mask);
        pbPreview = root.findViewById(R.id.studio_preview_progress);

        // Enforce 9:20 aspect ratio: width = height * (9/20)
        View previewFrame = root.findViewById(R.id.studio_preview_frame);
        previewFrame.post(() -> {
            int h = previewFrame.getHeight();
            if (h > 0) {
                int w = Math.round(h * 9f / 20f);
                ViewGroup.LayoutParams lp = previewFrame.getLayoutParams();
                lp.width = w;
                previewFrame.setLayoutParams(lp);
            }
        });

        loadPreviewImages();

        root.findViewById(R.id.btn_studio_reset).setOnClickListener(v -> {
            StudioManager.clearAll(requireContext());
            broadcastChange();
            refreshPreview();
            startSecondUpdaterIfNeeded();
            notifyStudioReset();
            Toast.makeText(requireContext(), "Studio reset to original", Toast.LENGTH_SHORT).show();
        });

        ViewPager2 pager = root.findViewById(R.id.studio_viewpager);
        pager.setAdapter(new StudioPagerAdapter(requireActivity()));
        pager.setOffscreenPageLimit(6);
        TabLayout tabs = root.findViewById(R.id.studio_tabs);
        new TabLayoutMediator(tabs, pager, (tab, pos) -> tab.setText(TAB_NAMES[pos])).attach();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPreviewImages();
        startSecondUpdaterIfNeeded();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopSecondUpdater();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopSecondUpdater();
    }

    private void loadPreviewImages() {
        // Composite rendering handles bg + mask + text together
        refreshPreview();
    }

    private Bitmap tryLoad(File f) {
        try { return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null; } catch (Exception e) { return null; }
    }

    public void scheduleRefresh() {
        debounce.removeCallbacks(pendingRefresh);
        pendingRefresh = this::refreshPreview;
        debounce.postDelayed(pendingRefresh, 100);
    }

    public void refreshPreview() {
        if (!isAdded()) return;
        // Don't show progress bar - user finds it distracting
        // pbPreview.setVisibility(View.VISIBLE);
        String themeJson = StudioManager.getEffectiveThemeJson(requireContext());

        // Get actual screen resolution for accurate preview (9:20 portrait)
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        requireActivity().getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int REF_W = dm.widthPixels > 0  ? dm.widthPixels  : 1080;
        // Use 9:20 ratio for preview height
        final int REF_H = REF_W > 0 ? (int)(REF_W * 20f / 9f) : 2400;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Bitmap composed = composePreview(requireContext(), themeJson, REF_W, REF_H);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (composed != null) {
                        ivBg.setVisibility(View.GONE);
                        ivMask.setVisibility(View.GONE);
                        ivText.setImageBitmap(composed);
                        ivText.setVisibility(View.VISIBLE);
                    }
                    // Hide progress bar (no longer shown)
                    pbPreview.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> { if (isAdded()) pbPreview.setVisibility(View.GONE); });
            }
        });

        // NOTE: Do NOT call startSecondUpdaterIfNeeded() from here.
        // refreshPreview() can be triggered by the ticker itself; starting/stopping inside it causes re-entrancy.
    }

    void startSecondUpdaterIfNeeded() {
        if (!isAdded()) return;
        boolean needsSeconds = false;
        try {
            JSONObject eff = new JSONObject(StudioManager.getEffectiveThemeJson(requireContext()));
            JSONObject time = eff.optJSONObject("time");
            if (time != null) {
                String style = time.optString("clockStyle", "HH:MM");
                needsSeconds = style != null && style.toUpperCase().contains("SS");
            }
        } catch (Exception ignored) {}

        if (!needsSeconds) {
            stopSecondUpdater();
            return;
        }

        // Always (re)start ticker on demand; handler callbacks may have been cleared
        secondsTickerRunning = true;

        if (secondRunnable == null) {
            secondRunnable = new Runnable() {
                @Override public void run() {
                    if (!isAdded()) {
                        stopSecondUpdater();
                        return;
                    }
                    scheduleRefresh();
                    secondHandler.postDelayed(this, 1000);
                }
            };
        }
        secondHandler.removeCallbacks(secondRunnable);
        long delay = 1000 - (System.currentTimeMillis() % 1000);
        secondHandler.postDelayed(secondRunnable, delay);
    }

    private void stopSecondUpdater() {
        secondsTickerRunning = false;
        if (secondRunnable != null) secondHandler.removeCallbacks(secondRunnable);
    }

    /** Compose bg + text + mask identically to MyWallpaperServiceNew depth-aware compositing */
    private Bitmap composePreview(android.content.Context ctx, String themeJson, int w, int h) {
        try {
            File dir      = new File(ctx.getFilesDir(), "wallpaper");
            File bgFile   = new File(dir, "bg.png");  if (!bgFile.exists())   bgFile   = new File(dir, "bg.jpg");
            File maskFile = new File(dir, "mask.png"); if (!maskFile.exists()) maskFile = new File(dir, "mask.jpg");

            Bitmap rawBg   = bgFile.exists()   ? BitmapFactory.decodeFile(bgFile.getAbsolutePath())   : null;
            Bitmap rawMask = maskFile.exists()  ? BitmapFactory.decodeFile(maskFile.getAbsolutePath()) : null;
            Bitmap bg   = rawBg   != null ? scaleCrop(rawBg,   w, h) : null;
            Bitmap mask = rawMask != null ? scaleCrop(rawMask, w, h) : null;
            if (rawBg   != null && rawBg   != bg)   rawBg.recycle();
            if (rawMask != null && rawMask != mask)  rawMask.recycle();

            // Get mask opacity from theme JSON
            float maskOpacity = 1.0f;
            try {
                JSONObject root = new JSONObject(themeJson);
                JSONObject time = root.optJSONObject("time");
                if (time != null) {
                    maskOpacity = (float) time.optDouble("maskOpacity", 1.0);
                }
            } catch (Exception ignored) {}

            ThemeRenderer tr = new ThemeRenderer(ctx);
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(result);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else            c.drawColor(android.graphics.Color.BLACK);

            // Paint for mask with opacity
            android.graphics.Paint maskPaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            maskPaint.setAlpha((int)(maskOpacity * 255));

            String depthMode = ThemeRenderer.getDepthMode(themeJson);
            if (!"none".equals(depthMode) && mask != null) {
                Bitmap back  = tr.renderBackLayer(themeJson, w, h, true, 0, 0);
                Bitmap front = tr.renderFrontLayer(themeJson, w, h, true, 0, 0);
                if (back  != null) { c.drawBitmap(back,  0, 0, p); back.recycle();  }
                c.drawBitmap(mask, 0, 0, maskPaint);
                if (front != null) { c.drawBitmap(front, 0, 0, p); front.recycle(); }
            } else {
                Bitmap textBmp = tr.renderThemeBitmap(themeJson, w, h, true, 0, 0);
                if (textBmp != null) { c.drawBitmap(textBmp, 0, 0, p); textBmp.recycle(); }
                if (mask    != null) { c.drawBitmap(mask,    0, 0, maskPaint); }
            }

            if (bg   != null) bg.recycle();
            if (mask != null) mask.recycle();
            return result;
        } catch (Exception e) { return null; }
    }

    private android.graphics.Bitmap scaleCrop(android.graphics.Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        float scaleX = (float) w / src.getWidth();
        float scaleY = (float) h / src.getHeight();
        float scale  = Math.max(scaleX, scaleY);
        int sw = Math.round(src.getWidth()  * scale);
        int sh = Math.round(src.getHeight() * scale);
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true);
        int offX = (sw - w) / 2, offY = (sh - h) / 2;
        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(scaled, offX, offY, w, h);
        if (scaled != src && scaled != cropped) scaled.recycle();
        return cropped;
    }

    public void broadcastChange() {
        try {
            Intent i = new Intent(SettingsManager.ACTION_SETTINGS_CHANGED);
            i.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private class StudioPagerAdapter extends FragmentStateAdapter {
        StudioPagerAdapter(FragmentActivity fa) { super(fa); }
        @Override public int getItemCount() { return TAB_NAMES.length; }
        @NonNull @Override public Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return new BasicsPage();
                case 1: return new TypographyPage();
                case 2: return new EffectsPage();
                case 3: return new TransformPage();
                case 4: return new DatePage();
                case 5: return new DateSettingsPage();
                default: return new BasicsPage();
            }
        }
    }

    static StudioFragment getStudio(Fragment child) {
        Fragment p = child.getParentFragment();
        if (p instanceof StudioFragment) return (StudioFragment) p;
        if (p != null && p.getParentFragment() instanceof StudioFragment) return (StudioFragment) p.getParentFragment();
        if (child.getActivity() != null)
            for (Fragment f : child.getActivity().getSupportFragmentManager().getFragments())
                if (f instanceof StudioFragment) return (StudioFragment) f;
        return null;
    }

    /** Returns the effective merged "time" object (base + overrides). */
    JSONObject getEffectiveTime() {
        try {
            String eff = StudioManager.getEffectiveThemeJson(requireContext());
            JSONObject root = new JSONObject(eff);
            JSONObject t = root.optJSONObject("time");
            return t != null ? t : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Returns the effective merged "date" object (base + overrides). */
    JSONObject getEffectiveDate() {
        try {
            String eff = StudioManager.getEffectiveThemeJson(requireContext());
            JSONObject root = new JSONObject(eff);
            JSONObject d = root.optJSONObject("date");
            return d != null ? d : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ── PAGE 1: Basics ───────────────────────────────────────────────────────
    public static class BasicsPage extends Fragment implements StudioFragment.OnStudioResetListener {
        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject t = st.getEffectiveTime();

            SeekBar seekSize = getView().findViewById(R.id.seek_size);
            TextView tvSize = getView().findViewById(R.id.tv_size_val);
            int size = (int) t.optDouble("size", 520);
            seekSize.setProgress(Math.min(1200, Math.max(0, size)));
            tvSize.setText(size + "sp");

            SeekBar seekX = getView().findViewById(R.id.seek_posx);
            TextView tvX = getView().findViewById(R.id.tv_posx_val);
            int x = (int) (t.optDouble("x", 0.5) * 100);
            seekX.setProgress(Math.min(100, Math.max(0, x)));
            tvX.setText(x + "%");

            SeekBar seekY = getView().findViewById(R.id.seek_posy);
            TextView tvY = getView().findViewById(R.id.tv_posy_val);
            int y = (int) (t.optDouble("y", 0.65) * 100);
            seekY.setProgress(Math.min(100, Math.max(0, y)));
            tvY.setText(y + "%");

            SeekBar seekOp = getView().findViewById(R.id.seek_opacity);
            TextView tvOp = getView().findViewById(R.id.tv_opacity_val);
            int op = (int) (t.optDouble("opacity", 1.0) * 100);
            seekOp.setProgress(Math.min(100, Math.max(0, op)));
            tvOp.setText(op + "%");
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_basics, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveTime = st.getEffectiveTime();

            SeekBar seekSize = v.findViewById(R.id.seek_size); TextView tvSize = v.findViewById(R.id.tv_size_val);
            int initSize = (int) effectiveTime.optDouble("size", 520);
            seekSize.setProgress(Math.min(1200, Math.max(0, initSize))); tvSize.setText(initSize + "sp");
            seekSize.setOnSeekBarChangeListener(simple(val -> { tvSize.setText(val + "sp"); StudioManager.setFontSize(requireContext(), val); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_size).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "size");
                JSONObject base = st.getEffectiveTime();
                int s2 = (int) base.optDouble("size", 520);
                seekSize.setProgress(Math.min(1200, Math.max(0, s2)));
                tvSize.setText(s2 + "sp");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SeekBar seekX = v.findViewById(R.id.seek_posx); TextView tvX = v.findViewById(R.id.tv_posx_val);
            int initX = (int)(effectiveTime.optDouble("x", 0.5) * 100); seekX.setProgress(initX); tvX.setText(initX + "%");
            seekX.setOnSeekBarChangeListener(simple(val -> { tvX.setText(val + "%"); StudioManager.setPosX(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_posx).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "x");
                JSONObject base = st.getEffectiveTime();
                int x2 = (int) (base.optDouble("x", 0.5) * 100);
                seekX.setProgress(Math.min(100, Math.max(0, x2)));
                tvX.setText(x2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SeekBar seekY = v.findViewById(R.id.seek_posy); TextView tvY = v.findViewById(R.id.tv_posy_val);
            int initY = (int)(effectiveTime.optDouble("y", 0.65) * 100); seekY.setProgress(initY); tvY.setText(initY + "%");
            seekY.setOnSeekBarChangeListener(simple(val -> { tvY.setText(val + "%"); StudioManager.setPosY(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_posy).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "y");
                JSONObject base = st.getEffectiveTime();
                int y2 = (int) (base.optDouble("y", 0.65) * 100);
                seekY.setProgress(Math.min(100, Math.max(0, y2)));
                tvY.setText(y2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Text Opacity (moved from Effects)
            SeekBar seekOp = v.findViewById(R.id.seek_opacity);
            TextView tvOp = v.findViewById(R.id.tv_opacity_val);
            int initOp = (int) (effectiveTime.optDouble("opacity", 1.0) * 100);
            seekOp.setProgress(initOp);
            tvOp.setText(initOp + "%");
            seekOp.setOnSeekBarChangeListener(simple(val -> {
                tvOp.setText(val + "%");
                StudioManager.setOpacity(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_opacity).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "opacity");
                JSONObject base = st.getEffectiveTime();
                int op2 = (int) (base.optDouble("opacity", 1.0) * 100);
                seekOp.setProgress(Math.min(100, Math.max(0, op2)));
                tvOp.setText(op2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // REMOVE duplicate declarations; seekX/seekY already exist above
            // SeekBar seekX = v.findViewById(R.id.seek_posx);
            // SeekBar seekY = v.findViewById(R.id.seek_posy);

            // +/- nudges (1% step)
            View minusSize = v.findViewById(R.id.btn_minus_size);
            View plusSize  = v.findViewById(R.id.btn_plus_size);
            if (minusSize != null) minusSize.setOnClickListener(btn -> nudgeSeek(seekSize, -1));
            if (plusSize  != null) plusSize.setOnClickListener(btn -> nudgeSeek(seekSize, +1));

            View minusX = v.findViewById(R.id.btn_minus_posx);
            View plusX  = v.findViewById(R.id.btn_plus_posx);
            if (minusX != null) minusX.setOnClickListener(btn -> nudgeSeek(seekX, -1));
            if (plusX  != null) plusX.setOnClickListener(btn -> nudgeSeek(seekX, +1));

            View minusY = v.findViewById(R.id.btn_minus_posy);
            View plusY  = v.findViewById(R.id.btn_plus_posy);
            if (minusY != null) minusY.setOnClickListener(btn -> nudgeSeek(seekY, -1));
            if (plusY  != null) plusY.setOnClickListener(btn -> nudgeSeek(seekY, +1));

            return v;
        }

        private void nudgeSeek(@Nullable SeekBar seek, int delta) {
            if (seek == null) return;
            int v = seek.getProgress() + delta;
            v = Math.max(0, Math.min(seek.getMax(), v));
            seek.setProgress(v);
        }
    }

    // ── PAGE 2: Typography ───────────────────────────────────────────────────
    public static class TypographyPage extends Fragment implements StudioFragment.OnStudioResetListener {

        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject t = st.getEffectiveTime();

            // Clock style
            RadioGroup rgStyle = getView().findViewById(R.id.rg_clock_style);
            String curStyle = t.optString("clockStyle", "HH:MM");
            if ("HHMM".equals(curStyle)) rgStyle.check(R.id.rb_style_hhmm);
            else if ("HH MM".equals(curStyle)) rgStyle.check(R.id.rb_style_hh_space_mm);
            else if ("HH.MM".equals(curStyle)) rgStyle.check(R.id.rb_style_hh_mm_dot);
            else if ("HH:MM:SS".equals(curStyle)) rgStyle.check(R.id.rb_style_hhmmss);
            else if ("HH/MM".equals(curStyle)) rgStyle.check(R.id.rb_style_hh_mm_slash);
            else if ("HH/MM/SS".equals(curStyle)) rgStyle.check(R.id.rb_style_hh_mm_ss_slash);
            else if ("VERTICAL".equals(curStyle)) rgStyle.check(R.id.rb_style_vertical);
            else if ("VERTICAL_SS".equals(curStyle)) rgStyle.check(R.id.rb_style_vertical_ss);
            else rgStyle.check(R.id.rb_style_hh_mm);

            // Depth
            RadioGroup rgDepth = getView().findViewById(R.id.rg_depth);
            String dm = t.optString("depthMode", "none");
            if ("hoursFront".equals(dm)) rgDepth.check(R.id.rb_depth_hoursfront);
            else if ("minuteFront".equals(dm)) rgDepth.check(R.id.rb_depth_minsfront);
            else rgDepth.check(R.id.rb_depth_standard);

            // Letter spacing
            SeekBar seekLs = getView().findViewById(R.id.seek_ls);
            TextView tvLs = getView().findViewById(R.id.tv_ls_val);
            int ls = (int) t.optDouble("letterSpacing", 0);
            seekLs.setProgress(Math.min(200, Math.max(0, ls + 100)));
            tvLs.setText(String.valueOf(ls));

            // Connector toggle
            SwitchCompat swConnector = getView().findViewById(R.id.sw_connector_behind);
            View connectorContainer = getView().findViewById(R.id.connector_toggle_container);
            boolean connectorBehind = t.optBoolean("connectorBehindMask", true);
            swConnector.setChecked(connectorBehind);
            boolean hasSeconds = curStyle.toUpperCase().contains("SS");
            if (connectorContainer != null) connectorContainer.setVisibility(hasSeconds ? View.VISIBLE : View.GONE);

            // Font list selection
            RecyclerView rvFonts = getView().findViewById(R.id.rv_fonts);
            if (rvFonts != null && rvFonts.getAdapter() instanceof FontPickerAdapter) {
                ((FontPickerAdapter) rvFonts.getAdapter()).setSelected(t.optString("font", "main3.ttf"));
            }

            // Color pickers
            InlineColorPicker cpHour = getView().findViewById(R.id.color_picker_hour);
            View swHour = getView().findViewById(R.id.swatch_hour);
            String h = t.optString("hourColor", "#FFFFFF");
            if (cpHour != null) cpHour.setSelectedColor(h);
            if (swHour != null) trySetBg(swHour, h);

            InlineColorPicker cpMin = getView().findViewById(R.id.color_picker_minute);
            View swMin = getView().findViewById(R.id.swatch_minute);
            String m = t.optString("minuteColor", "#FF5FA2");
            if (cpMin != null) cpMin.setSelectedColor(m);
            if (swMin != null) trySetBg(swMin, m);

            // Gradient toggle + angle
            SwitchCompat swGrad = getView().findViewById(R.id.sw_time_gradient);
            View gradAngleLayout = getView().findViewById(R.id.layout_time_gradient_angle);
            SeekBar seekGradAng = getView().findViewById(R.id.seek_time_gradient_angle);
            TextView tvGradAng = getView().findViewById(R.id.tv_time_gradient_angle_val);
            if (swGrad != null) {
                boolean ge = t.optBoolean("timeGradientEnabled", false);
                swGrad.setChecked(ge);
                if (gradAngleLayout != null) gradAngleLayout.setVisibility(ge ? View.VISIBLE : View.GONE);
            }
            if (seekGradAng != null) {
                int ang = (int) (t.optDouble("timeGradientAngle", 0) % 360);
                if (ang < 0) ang += 360;
                seekGradAng.setProgress(ang);
                if (tvGradAng != null) tvGradAng.setText(ang + "°");
            }

            // ensure seconds ticking is correct after reset
            st.startSecondUpdaterIfNeeded();
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_typography, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveTime = st.getEffectiveTime();

            // Clock Style
            RadioGroup rgStyle = v.findViewById(R.id.rg_clock_style);
            String curStyle = effectiveTime.optString("clockStyle", "HH:MM");
            if ("HHMM".equals(curStyle))         rgStyle.check(R.id.rb_style_hhmm);
            else if ("HH MM".equals(curStyle))   rgStyle.check(R.id.rb_style_hh_space_mm);
            else if ("HH.MM".equals(curStyle))   rgStyle.check(R.id.rb_style_hh_mm_dot);
            else if ("HH:MM:SS".equals(curStyle)) rgStyle.check(R.id.rb_style_hhmmss);
            else if ("HH/MM".equals(curStyle))   rgStyle.check(R.id.rb_style_hh_mm_slash);
            else if ("HH/MM/SS".equals(curStyle)) rgStyle.check(R.id.rb_style_hh_mm_ss_slash);
            else if ("VERTICAL".equals(curStyle)) rgStyle.check(R.id.rb_style_vertical);
            else if ("VERTICAL_SS".equals(curStyle)) rgStyle.check(R.id.rb_style_vertical_ss);
            else                                 rgStyle.check(R.id.rb_style_hh_mm);
            // Clock style listener is set below after connector toggle setup

            // Depth
            RadioGroup rgDepth = v.findViewById(R.id.rg_depth);
            String curDepth = effectiveTime.optString("depthMode", "none");
            if ("hoursFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_hoursfront);
            else if ("minuteFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_minsfront);
            else rgDepth.check(R.id.rb_depth_standard);
            rgDepth.setOnCheckedChangeListener((g, id) -> { String dm = id == R.id.rb_depth_hoursfront ? "hoursFront" : id == R.id.rb_depth_minsfront ? "minuteFront" : "none"; StudioManager.setDepthMode(requireContext(), dm); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_depth).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "depthMode");
                String dm = st.getEffectiveTime().optString("depthMode", "none");
                if ("hoursFront".equals(dm)) rgDepth.check(R.id.rb_depth_hoursfront);
                else if ("minuteFront".equals(dm)) rgDepth.check(R.id.rb_depth_minsfront);
                else rgDepth.check(R.id.rb_depth_standard);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Connector Behind Mask Toggle (only visible when seconds clock style is selected)
            View connectorContainer = v.findViewById(R.id.connector_toggle_container);
            SwitchCompat swConnector = v.findViewById(R.id.sw_connector_behind);
            boolean connectorBehind = effectiveTime.optBoolean("connectorBehindMask", true);
            swConnector.setChecked(connectorBehind);
            swConnector.setOnCheckedChangeListener((b, ch) -> {
                StudioManager.setConnectorBehindMask(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Helper to check if clock style has seconds
            Runnable updateConnectorVisibility = () -> {
                int checkedId = rgStyle.getCheckedRadioButtonId();
                boolean hasSeconds = (checkedId == R.id.rb_style_hhmmss || checkedId == R.id.rb_style_hh_mm_ss_slash
                        || checkedId == R.id.rb_style_vertical_ss);
                connectorContainer.setVisibility(hasSeconds ? View.VISIBLE : View.GONE);
            };

            // Initial visibility based on current style
            updateConnectorVisibility.run();

            // Update visibility when clock style changes
            rgStyle.setOnCheckedChangeListener((g, id) -> {
                String st2 = id == R.id.rb_style_hhmm ? "HHMM"
                        : id == R.id.rb_style_hh_space_mm ? "HH MM"
                        : id == R.id.rb_style_hh_mm_dot ? "HH.MM"
                        : id == R.id.rb_style_hhmmss ? "HH:MM:SS"
                        : id == R.id.rb_style_hh_mm_slash ? "HH/MM"
                        : id == R.id.rb_style_hh_mm_ss_slash ? "HH/MM/SS"
                        : id == R.id.rb_style_vertical ? "VERTICAL"
                        : id == R.id.rb_style_vertical_ss ? "VERTICAL_SS"
                        : "HH:MM";
                StudioManager.setClockStyle(requireContext(), st2);
                st.scheduleRefresh();
                st.broadcastChange();
                updateConnectorVisibility.run();
                // Re-evaluate seconds ticker when style changes
                st.startSecondUpdaterIfNeeded();
            });
            v.findViewById(R.id.btn_reset_clock_style).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "clockStyle");
                String cur = st.getEffectiveTime().optString("clockStyle", "HH:MM");
                if ("HHMM".equals(cur)) rgStyle.check(R.id.rb_style_hhmm);
                else if ("HH MM".equals(cur)) rgStyle.check(R.id.rb_style_hh_space_mm);
                else if ("HH.MM".equals(cur)) rgStyle.check(R.id.rb_style_hh_mm_dot);
                else if ("HH:MM:SS".equals(cur)) rgStyle.check(R.id.rb_style_hhmmss);
                else if ("HH/MM".equals(cur)) rgStyle.check(R.id.rb_style_hh_mm_slash);
                else if ("HH/MM/SS".equals(cur)) rgStyle.check(R.id.rb_style_hh_mm_ss_slash);
                else if ("VERTICAL".equals(cur)) rgStyle.check(R.id.rb_style_vertical);
                else if ("VERTICAL_SS".equals(cur)) rgStyle.check(R.id.rb_style_vertical_ss);
                else rgStyle.check(R.id.rb_style_hh_mm);
                st.scheduleRefresh();
                st.broadcastChange();
                updateConnectorVisibility.run();
                st.startSecondUpdaterIfNeeded();
            });

            // Font
            RecyclerView rvFonts = v.findViewById(R.id.rv_fonts);
            rvFonts.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            FontPickerAdapter fa = new FontPickerAdapter(requireContext(), Arrays.asList(FONTS), effectiveTime.optString("font", "main3.ttf"));
            fa.setListener(font -> { StudioManager.setFont(requireContext(), font); st.scheduleRefresh(); st.broadcastChange(); });
            rvFonts.setAdapter(fa);
            v.findViewById(R.id.btn_reset_font).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "font"); fa.setSelected("main3.ttf"); st.scheduleRefresh(); st.broadcastChange(); });

            // Letter Spacing (seekbar 0-200 maps to -100 to +100)
            SeekBar seekLs = v.findViewById(R.id.seek_ls); TextView tvLs = v.findViewById(R.id.tv_ls_val);
            int initLs = (int) effectiveTime.optDouble("letterSpacing", 0);
            int seekVal = initLs + 100; // Convert -100..+100 to 0..200
            seekLs.setProgress(Math.min(200, Math.max(0, seekVal)));
            tvLs.setText(String.valueOf(initLs));
            seekLs.setOnSeekBarChangeListener(simple(val -> {
                int realVal = val - 100; // Convert 0..200 back to -100..+100
                tvLs.setText(String.valueOf(realVal));
                StudioManager.setLetterSpacing(requireContext(), realVal);
                st.scheduleRefresh(); st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_ls).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "letterSpacing");
                int ls2 = (int) st.getEffectiveTime().optDouble("letterSpacing", 0);
                seekLs.setProgress(Math.min(200, Math.max(0, ls2 + 100)));
                tvLs.setText(String.valueOf(ls2));
                st.scheduleRefresh();
                st.broadcastChange();
            });

            View minusLs = v.findViewById(R.id.btn_minus_ls);
            View plusLs  = v.findViewById(R.id.btn_plus_ls);
            if (minusLs != null) minusLs.setOnClickListener(btn -> nudgeSeek(seekLs, -1));
            if (plusLs  != null) plusLs.setOnClickListener(btn -> nudgeSeek(seekLs, +1));

            // Hour Color picker
            String[] hc = { effectiveTime.optString("hourColor", "#FFFFFF") };
            View swHour = v.findViewById(R.id.swatch_hour); trySetBg(swHour, hc[0]);
            InlineColorPicker cpHour = v.findViewById(R.id.color_picker_hour);
            cpHour.setSelectedColor(hc[0]);
            cpHour.setOnColorSelectedListener(hex -> { hc[0] = hex; trySetBg(swHour, hex); StudioManager.setHourColor(requireContext(), hex); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_hour_color).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "hourColor");
                // pull from base theme (database) after reset
                JSONObject base = st.getEffectiveTime();
                hc[0] = base.optString("hourColor", "#FFFFFF");
                trySetBg(swHour, hc[0]);
                cpHour.setSelectedColor(hc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Minute Color picker
            String[] mc = { effectiveTime.optString("minuteColor", "#FF5FA2") };
            View swMin = v.findViewById(R.id.swatch_minute); trySetBg(swMin, mc[0]);
            InlineColorPicker cpMin = v.findViewById(R.id.color_picker_minute);
            cpMin.setSelectedColor(mc[0]);
            cpMin.setOnColorSelectedListener(hex -> { mc[0] = hex; trySetBg(swMin, hex); StudioManager.setMinuteColor(requireContext(), hex); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_min_color).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "minuteColor");
                JSONObject base = st.getEffectiveTime();
                mc[0] = base.optString("minuteColor", "#FF5FA2");
                trySetBg(swMin, mc[0]);
                cpMin.setSelectedColor(mc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Time gradient toggle + angle
            SwitchCompat swGrad = v.findViewById(R.id.sw_time_gradient);
            View gradAngleLayout = v.findViewById(R.id.layout_time_gradient_angle);
            SeekBar seekGradAng = v.findViewById(R.id.seek_time_gradient_angle);
            TextView tvGradAng = v.findViewById(R.id.tv_time_gradient_angle_val);

            boolean initGrad = effectiveTime.optBoolean("timeGradientEnabled", false);
            float initAng = (float) effectiveTime.optDouble("timeGradientAngle", 0);
            swGrad.setChecked(initGrad);
            gradAngleLayout.setVisibility(initGrad ? View.VISIBLE : View.GONE);
            seekGradAng.setProgress((int) (initAng % 360f + 360f) % 360);
            tvGradAng.setText(((int) initAng) + "°");

            swGrad.setOnCheckedChangeListener((b2, ch) -> {
                gradAngleLayout.setVisibility(ch ? View.VISIBLE : View.GONE);
                StudioManager.setTimeGradientEnabled(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            seekGradAng.setOnSeekBarChangeListener(simple(val -> {
                float ang = val;
                tvGradAng.setText(((int) ang) + "°");
                StudioManager.setTimeGradientAngle(requireContext(), ang);
                st.scheduleRefresh();
                st.broadcastChange();
            }));

            View minusAng = v.findViewById(R.id.btn_minus_time_gradient_angle);
            View plusAng  = v.findViewById(R.id.btn_plus_time_gradient_angle);
            if (minusAng != null) minusAng.setOnClickListener(btn -> nudgeSeek(seekGradAng, -1));
            if (plusAng  != null) plusAng.setOnClickListener(btn -> nudgeSeek(seekGradAng, +1));

            // ...existing Typography controls (stroke, glow, etc.)...

            return v;
        }

        private void nudgeSeek(@Nullable SeekBar seek, int delta) {
            if (seek == null) return;
            int v = seek.getProgress() + delta;
            v = Math.max(0, Math.min(seek.getMax(), v));
            seek.setProgress(v);
        }
    }

    // ── PAGE 3: Effects ──────────────────────────────────────────────────────
    public static class EffectsPage extends Fragment implements StudioFragment.OnStudioResetListener {

        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject t = st.getEffectiveTime();

            // Shadow
            SwitchCompat swShadow = getView().findViewById(R.id.sw_shadow);
            View shadowCtrl = getView().findViewById(R.id.layout_shadow_controls);
            boolean se = t.optBoolean("shadowEnabled", false);
            if (swShadow != null) swShadow.setChecked(se);
            if (shadowCtrl != null) shadowCtrl.setVisibility(se ? View.VISIBLE : View.GONE);

            SeekBar seekShOp = getView().findViewById(R.id.seek_shadow_opacity);
            TextView tvShOp = getView().findViewById(R.id.tv_shadow_opacity_val);
            int op = (int) (t.optDouble("shadowOpacity", 1.0) * 100);
            if (seekShOp != null) seekShOp.setProgress(Math.min(100, Math.max(0, op)));
            if (tvShOp != null) tvShOp.setText(op + "%");

            SeekBar seekSx = getView().findViewById(R.id.seek_shadow_x);
            SeekBar seekSy = getView().findViewById(R.id.seek_shadow_y);
            TextView tvSx = getView().findViewById(R.id.tv_shadow_x_val);
            TextView tvSy = getView().findViewById(R.id.tv_shadow_y_val);
            int sx = (int) t.optDouble("shadowX", 4);
            int sy = (int) t.optDouble("shadowY", 4);
            if (seekSx != null) seekSx.setProgress(sx + 100);
            if (tvSx != null) tvSx.setText(String.valueOf(sx));
            if (seekSy != null) seekSy.setProgress(sy + 100);
            if (tvSy != null) tvSy.setText(String.valueOf(sy));

            // Stroke
            SwitchCompat swStroke = getView().findViewById(R.id.sw_stroke);
            View strokeCtrl = getView().findViewById(R.id.layout_stroke_controls);
            boolean ste = t.optBoolean("strokeEnabled", false);
            if (swStroke != null) swStroke.setChecked(ste);
            if (strokeCtrl != null) strokeCtrl.setVisibility(ste ? View.VISIBLE : View.GONE);

            SeekBar seekStrokeW = getView().findViewById(R.id.seek_stroke_w);
            TextView tvStrokeW = getView().findViewById(R.id.tv_stroke_w_val);
            int sw = (int) t.optDouble("strokeWidth", 0);
            if (seekStrokeW != null) seekStrokeW.setProgress(Math.min(50, Math.max(0, sw)));
            if (tvStrokeW != null) tvStrokeW.setText(String.valueOf(sw));

            InlineColorPicker cpStroke = getView().findViewById(R.id.color_picker_stroke);
            View swStrokeCol = getView().findViewById(R.id.swatch_stroke);
            String scol = t.optString("strokeColor", "#000000");
            if (cpStroke != null) cpStroke.setSelectedColor(scol);
            if (swStrokeCol != null) trySetBg(swStrokeCol, scol);

            RadioGroup rgTarget = getView().findViewById(R.id.rg_stroke_target);
            String target = t.optString("strokeTarget", "both");
            if (rgTarget != null) {
                int id;
                if ("hh".equals(target)) id = R.id.rb_stroke_hh;
                else if ("mm".equals(target)) id = R.id.rb_stroke_mm;
                else id = R.id.rb_stroke_both;
                rgTarget.check(id);
            }

            SwitchCompat swFill = getView().findViewById(R.id.sw_fill_enabled);
            boolean fill = t.optBoolean("fillEnabled", true);
            if (swFill != null) swFill.setChecked(fill);

            // Mask opacity
            SeekBar seekMask = getView().findViewById(R.id.seek_mask_opacity);
            TextView tvMask = getView().findViewById(R.id.tv_mask_opacity_val);
            int mo = (int) (t.optDouble("maskOpacity", 1.0) * 100);
            if (seekMask != null) seekMask.setProgress(Math.min(100, Math.max(0, mo)));
            if (tvMask != null) tvMask.setText(mo + "%");

            // Glow
            SeekBar seekGlow = getView().findViewById(R.id.seek_glow_radius);
            TextView tvGlow = getView().findViewById(R.id.tv_glow_radius_val);
            int gr = (int) t.optDouble("glowRadius", 0);
            if (seekGlow != null) seekGlow.setProgress(Math.min(200, Math.max(0, gr)));
            if (tvGlow != null) tvGlow.setText(String.valueOf(gr));

            InlineColorPicker cpGlow = getView().findViewById(R.id.color_picker_glow);
            View swGlow = getView().findViewById(R.id.swatch_glow);
            String gcol = t.optString("glowColor", t.optString("minuteColor", "#FFFFFF"));
            if (cpGlow != null) cpGlow.setSelectedColor(gcol);
            if (swGlow != null) trySetBg(swGlow, gcol);
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_effects, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveTime = st.getEffectiveTime();

            // ── Shadow Toggle + Controls ─────────────────────────────────────
            SwitchCompat swShadow = v.findViewById(R.id.sw_shadow);
            View shadowCtrl = v.findViewById(R.id.layout_shadow_controls);
            boolean initShad = effectiveTime.optBoolean("shadowEnabled", false);
            swShadow.setChecked(initShad);
            shadowCtrl.setVisibility(initShad ? View.VISIBLE : View.GONE);
            swShadow.setOnCheckedChangeListener((b2, ch) -> {
                shadowCtrl.setVisibility(ch ? View.VISIBLE : View.GONE);
                StudioManager.setShadowEnabled(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SeekBar seekShOp = v.findViewById(R.id.seek_shadow_opacity);
            TextView tvShOp = v.findViewById(R.id.tv_shadow_opacity_val);
            int initShOp = (int) (effectiveTime.optDouble("shadowOpacity", 1.0) * 100);
            seekShOp.setProgress(Math.min(100, Math.max(0, initShOp)));
            tvShOp.setText(initShOp + "%");
            seekShOp.setOnSeekBarChangeListener(simple(val -> {
                tvShOp.setText(val + "%");
                StudioManager.setShadowOpacity(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_shadow_opacity).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "shadowOpacity");
                int v2 = (int) (st.getEffectiveTime().optDouble("shadowOpacity", 1.0) * 100);
                seekShOp.setProgress(Math.min(100, Math.max(0, v2)));
                tvShOp.setText(v2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SeekBar seekSx = v.findViewById(R.id.seek_shadow_x);
            SeekBar seekSy = v.findViewById(R.id.seek_shadow_y);
            TextView tvSx = v.findViewById(R.id.tv_shadow_x_val);
            TextView tvSy = v.findViewById(R.id.tv_shadow_y_val);
            int initShadowX = (int) effectiveTime.optDouble("shadowX", 4);
            int initShadowY = (int) effectiveTime.optDouble("shadowY", 4);
            seekSx.setProgress(initShadowX + 100);
            tvSx.setText(String.valueOf(initShadowX));
            seekSy.setProgress(initShadowY + 100);
            tvSy.setText(String.valueOf(initShadowY));
            seekSx.setOnSeekBarChangeListener(simple(val -> {
                int realVal = val - 100;
                tvSx.setText(String.valueOf(realVal));
                StudioManager.setShadowX(requireContext(), realVal);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            seekSy.setOnSeekBarChangeListener(simple(val -> {
                int realVal = val - 100;
                tvSy.setText(String.valueOf(realVal));
                StudioManager.setShadowY(requireContext(), realVal);
                st.scheduleRefresh();
                st.broadcastChange();
            }));

            v.findViewById(R.id.btn_reset_shadow).setOnClickListener(b -> {
                swShadow.setChecked(false);
                StudioManager.resetTimeKey(requireContext(), "shadowEnabled");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_shadow_x).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "shadowX");
                int v2 = (int) st.getEffectiveTime().optDouble("shadowX", 4);
                seekSx.setProgress(v2 + 100);
                tvSx.setText(String.valueOf(v2));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_shadow_y).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "shadowY");
                int v2 = (int) st.getEffectiveTime().optDouble("shadowY", 4);
                seekSy.setProgress(v2 + 100);
                tvSy.setText(String.valueOf(v2));
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Stroke Toggle + Controls ─────────────────────────────────────
            SwitchCompat swStroke = v.findViewById(R.id.sw_stroke);
            View strokeCtrl = v.findViewById(R.id.layout_stroke_controls);
            boolean initStroke = effectiveTime.optBoolean("strokeEnabled", false);
            swStroke.setChecked(initStroke);
            strokeCtrl.setVisibility(initStroke ? View.VISIBLE : View.GONE);
            swStroke.setOnCheckedChangeListener((b2, ch) -> {
                strokeCtrl.setVisibility(ch ? View.VISIBLE : View.GONE);
                StudioManager.setStrokeEnabled(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SeekBar seekStrokeW = v.findViewById(R.id.seek_stroke_w);
            TextView tvStrokeW = v.findViewById(R.id.tv_stroke_w_val);
            int initSW = (int) effectiveTime.optDouble("strokeWidth", 0);
            seekStrokeW.setProgress(Math.min(50, Math.max(0, initSW)));
            tvStrokeW.setText(String.valueOf(initSW));
            seekStrokeW.setOnSeekBarChangeListener(simple(val -> {
                tvStrokeW.setText(String.valueOf(val));
                StudioManager.setStrokeWidth(requireContext(), val);
                st.scheduleRefresh();
                st.broadcastChange();
            }));

            InlineColorPicker cpStroke = v.findViewById(R.id.color_picker_stroke);
            View swStrokeCol = v.findViewById(R.id.swatch_stroke);
            String initStrokeCol = effectiveTime.optString("strokeColor", "#000000");
            trySetBg(swStrokeCol, initStrokeCol);
            if (cpStroke != null) {
                cpStroke.setSelectedColor(initStrokeCol);
                cpStroke.setOnColorSelectedListener(col -> {
                    trySetBg(swStrokeCol, col);
                    StudioManager.setStrokeColor(requireContext(), col);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            RadioGroup rgTarget = v.findViewById(R.id.rg_stroke_target);
            String initTarget = effectiveTime.optString("strokeTarget", "both");
            if (rgTarget != null) {
                if ("hh".equals(initTarget)) rgTarget.check(R.id.rb_stroke_hh);
                else if ("mm".equals(initTarget)) rgTarget.check(R.id.rb_stroke_mm);
                else rgTarget.check(R.id.rb_stroke_both);

                rgTarget.setOnCheckedChangeListener((g, checkedId) -> {
                    String tgt = "both";
                    if (checkedId == R.id.rb_stroke_hh) tgt = "hh";
                    else if (checkedId == R.id.rb_stroke_mm) tgt = "mm";
                    StudioManager.setStrokeTarget(requireContext(), tgt);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            SwitchCompat swFill = v.findViewById(R.id.sw_fill_enabled);
            boolean initFill = effectiveTime.optBoolean("fillEnabled", true);
            if (swFill != null) {
                swFill.setChecked(initFill);
                swFill.setOnCheckedChangeListener((b2, ch) -> {
                    StudioManager.setFillEnabled(requireContext(), ch);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            v.findViewById(R.id.btn_reset_stroke).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "strokeEnabled");
                StudioManager.resetTimeKey(requireContext(), "strokeWidth");
                StudioManager.resetTimeKey(requireContext(), "strokeColor");
                StudioManager.resetTimeKey(requireContext(), "strokeTarget");
                // keep fill as-is
                onStudioReset();
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Mask opacity ────────────────────────────────────────────────
            SeekBar seekMask = v.findViewById(R.id.seek_mask_opacity);
            TextView tvMask = v.findViewById(R.id.tv_mask_opacity_val);
            int initMask = (int) (effectiveTime.optDouble("maskOpacity", 1.0) * 100);
            seekMask.setProgress(Math.min(100, Math.max(0, initMask)));
            tvMask.setText(initMask + "%");
            seekMask.setOnSeekBarChangeListener(simple(val -> {
                tvMask.setText(val + "%");
                StudioManager.setMaskOpacity(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_mask_opacity).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "maskOpacity");
                int v2 = (int) (st.getEffectiveTime().optDouble("maskOpacity", 1.0) * 100);
                seekMask.setProgress(Math.min(100, Math.max(0, v2)));
                tvMask.setText(v2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Glow ───────────────────────────────────────────────────────
            SeekBar seekGlow = v.findViewById(R.id.seek_glow_radius);
            TextView tvGlow = v.findViewById(R.id.tv_glow_radius_val);
            int initGlow = (int) effectiveTime.optDouble("glowRadius", 0);
            seekGlow.setProgress(Math.min(200, Math.max(0, initGlow)));
            tvGlow.setText(String.valueOf(initGlow));
            seekGlow.setOnSeekBarChangeListener(simple(val -> {
                tvGlow.setText(String.valueOf(val));
                StudioManager.setGlowRadius(requireContext(), val);
                st.scheduleRefresh();
                st.broadcastChange();
            }));

            InlineColorPicker cpGlow = v.findViewById(R.id.color_picker_glow);
            View swGlow = v.findViewById(R.id.swatch_glow);
            String initGlowCol = effectiveTime.optString("glowColor", effectiveTime.optString("minuteColor", "#FFFFFF"));
            trySetBg(swGlow, initGlowCol);
            if (cpGlow != null) {
                cpGlow.setSelectedColor(initGlowCol);
                cpGlow.setOnColorSelectedListener(col -> {
                    trySetBg(swGlow, col);
                    StudioManager.setGlowColor(requireContext(), col);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            v.findViewById(R.id.btn_reset_glow).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "glowRadius");
                StudioManager.resetTimeKey(requireContext(), "glowColor");
                onStudioReset();
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // One-time restore to ensure all controls reflect effective theme (including after main reset)
            v.post(this::onStudioReset);
            return v;
        }
    }

    // ── PAGE 4: Transform ────────────────────────────────────────────────────
    public static class TransformPage extends Fragment implements StudioFragment.OnStudioResetListener {

        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject t = st.getEffectiveTime();

            // Rotation
            SeekBar seekRot = getView().findViewById(R.id.seek_rot);
            TextView tvRot = getView().findViewById(R.id.tv_rot_val);
            float rot = (float) t.optDouble("rotation", 0);
            if (seekRot != null) seekRot.setProgress((int) (rot + 180));
            if (tvRot != null) tvRot.setText((int) rot + "°");

            // Stretch
            SeekBar seekSx = getView().findViewById(R.id.seek_sx);
            TextView tvSx = getView().findViewById(R.id.tv_sx_val);
            int sx = (int) (t.optDouble("stretchX", 1.0) * 100);
            if (seekSx != null) seekSx.setProgress(Math.min(600, Math.max(0, sx)));
            if (tvSx != null) tvSx.setText(Math.min(600, Math.max(0, sx)) + "%");

            SeekBar seekSy = getView().findViewById(R.id.seek_sy);
            TextView tvSy = getView().findViewById(R.id.tv_sy_val);
            int sy = (int) (t.optDouble("stretchY", 1.0) * 100);
            if (seekSy != null) seekSy.setProgress(Math.min(600, Math.max(0, sy)));
            if (tvSy != null) tvSy.setText(Math.min(600, Math.max(0, sy)) + "%");

            // Skews
            SeekBar seekSkH = getView().findViewById(R.id.seek_skewh);
            TextView tvSkH = getView().findViewById(R.id.tv_skewh_val);
            float skh = (float) t.optDouble("skewH", 0);
            if (seekSkH != null) seekSkH.setProgress((int) (skh * 100 + 200));
            if (tvSkH != null) tvSkH.setText((int) (skh * 100) + "%");

            SeekBar seekSkV = getView().findViewById(R.id.seek_skewv);
            TextView tvSkV = getView().findViewById(R.id.tv_skewv_val);
            float skv = (float) t.optDouble("skewV", 0);
            if (seekSkV != null) seekSkV.setProgress((int) (skv * 100 + 200));
            if (tvSkV != null) tvSkV.setText((int) (skv * 100) + "%");

            SeekBar seekBh = getView().findViewById(R.id.seek_skewbh);
            TextView tvBh = getView().findViewById(R.id.tv_skewbh_val);
            float b = (float) t.optDouble("skewBottomH", 0);
            if (seekBh != null) seekBh.setProgress((int) (b * 100 + 200));
            if (tvBh != null) tvBh.setText((int) (b * 100) + "%");

            SeekBar seekLv = getView().findViewById(R.id.seek_skewlv);
            TextView tvLv = getView().findViewById(R.id.tv_skewlv_val);
            float lv = (float) t.optDouble("skewLeftV", 0);
            if (seekLv != null) seekLv.setProgress((int) (lv * 100 + 200));
            if (tvLv != null) tvLv.setText((int) (lv * 100) + "%");

            SeekBar seekLo = getView().findViewById(R.id.seek_skewlo);
            TextView tvLo = getView().findViewById(R.id.tv_skewlo_val);
            float lo = (float) t.optDouble("skewLeftOnly", 0);
            if (seekLo != null) seekLo.setProgress((int) (lo * 100 + 200));
            if (tvLo != null) tvLo.setText((int) (lo * 100) + "%");

            // Curvature
            SeekBar seekCurve = getView().findViewById(R.id.seek_curve);
            TextView tvCurve = getView().findViewById(R.id.tv_curve_val);
            int curve = (int) Math.round(t.optDouble("curvature", 0.0) * 100);
            curve = Math.max(-100, Math.min(100, curve));
            if (seekCurve != null) seekCurve.setProgress(curve + 100);
            if (tvCurve != null) tvCurve.setText(curve + "%");
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_transform, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveTime = st.getEffectiveTime();

            // Rotation (-180 to +180 mapped to seekbar 0-360, mid=180)
            SeekBar seekRot = v.findViewById(R.id.seek_rot); TextView tvRot = v.findViewById(R.id.tv_rot_val);
            float initRot = (float) effectiveTime.optDouble("rotation", 0); seekRot.setProgress((int)(initRot + 180)); tvRot.setText((int)initRot + "°");
            seekRot.setOnSeekBarChangeListener(simple(val -> { float d = val - 180f; tvRot.setText((int)d + "°"); StudioManager.setRotation(requireContext(), d); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_rot).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "rotation"); float r2 = (float) st.getEffectiveTime().optDouble("rotation", 0); seekRot.setProgress((int) (r2 + 180)); tvRot.setText((int) r2 + "°"); st.scheduleRefresh(); st.broadcastChange(); });

            // Stretch X (0-600 → 0%-600%)
            SeekBar seekSx = v.findViewById(R.id.seek_sx); TextView tvSx = v.findViewById(R.id.tv_sx_val);
            float initSx = (float) effectiveTime.optDouble("stretchX", 1.0);
            int initSxPct = (int) (initSx * 100);
            seekSx.setProgress(Math.min(600, Math.max(0, initSxPct)));
            tvSx.setText(Math.min(600, Math.max(0, initSxPct)) + "%");
            seekSx.setOnSeekBarChangeListener(simple(val -> { float sv = val / 100f; tvSx.setText(val + "%"); StudioManager.setStretchX(requireContext(), sv); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_sx).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "stretchX"); int pct = (int) (st.getEffectiveTime().optDouble("stretchX", 1.0) * 100); seekSx.setProgress(Math.min(600, Math.max(0, pct))); tvSx.setText(Math.min(600, Math.max(0, pct)) + "%"); st.scheduleRefresh(); st.broadcastChange(); });

            // Stretch Y (0-600 → 0%-600%)
            SeekBar seekSy = v.findViewById(R.id.seek_sy); TextView tvSy = v.findViewById(R.id.tv_sy_val);
            float initSy = (float) effectiveTime.optDouble("stretchY", 1.0);
            int initSyPct = (int) (initSy * 100);
            seekSy.setProgress(Math.min(600, Math.max(0, initSyPct)));
            tvSy.setText(Math.min(600, Math.max(0, initSyPct)) + "%");
            seekSy.setOnSeekBarChangeListener(simple(val -> { float sv = val / 100f; tvSy.setText(val + "%"); StudioManager.setStretchY(requireContext(), sv); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_sy).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "stretchY"); int pct = (int) (st.getEffectiveTime().optDouble("stretchY", 1.0) * 100); seekSy.setProgress(Math.min(600, Math.max(0, pct))); tvSy.setText(Math.min(600, Math.max(0, pct)) + "%"); st.scheduleRefresh(); st.broadcastChange(); });

            // Skew H (-200% to +200%, seekbar 0-400, center=200)
            SeekBar seekSH = v.findViewById(R.id.seek_skewh); TextView tvSH = v.findViewById(R.id.tv_skewh_val);
            float initSH = (float) effectiveTime.optDouble("skewH", 0);
            seekSH.setProgress((int)(initSH * 100 + 200));
            tvSH.setText((int)(initSH * 100) + "%");
            seekSH.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 200) / 100f;
                tvSH.setText((val - 200) + "%");
                StudioManager.setSkewH(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewh).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewH");
                seekSH.setProgress(200);
                tvSH.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Skew V (-200% to +200%, seekbar 0-400, center=200)
            SeekBar seekSV = v.findViewById(R.id.seek_skewv); TextView tvSV = v.findViewById(R.id.tv_skewv_val);
            float initSV = (float) effectiveTime.optDouble("skewV", 0);
            seekSV.setProgress((int)(initSV * 100 + 200));
            tvSV.setText((int)(initSV * 100) + "%");
            seekSV.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 200) / 100f;
                tvSV.setText((val - 200) + "%");
                StudioManager.setSkewV(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewv).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewV");
                seekSV.setProgress(200);
                tvSV.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Bottom Skew (-200% to +200%, seekbar 0-400, center=200)
            SeekBar seekBH = v.findViewById(R.id.seek_skewbh); TextView tvBH = v.findViewById(R.id.tv_skewbh_val);
            float initBH = (float) effectiveTime.optDouble("skewBottomH", 0);
            seekBH.setProgress((int)(initBH * 100 + 200));
            tvBH.setText((int)(initBH * 100) + "%");
            seekBH.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 200) / 100f;
                tvBH.setText((val - 200) + "%");
                StudioManager.setSkewBottomH(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewbh).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewBottomH");
                seekBH.setProgress(200);
                tvBH.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Shear V (-200% to +200%, seekbar 0-400, center=200)
            SeekBar seekLV = v.findViewById(R.id.seek_skewlv); TextView tvLV = v.findViewById(R.id.tv_skewlv_val);
            float initLV = (float) effectiveTime.optDouble("skewLeftV", 0);
            seekLV.setProgress((int)(initLV * 100 + 200));
            tvLV.setText((int)(initLV * 100) + "%");
            seekLV.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 200) / 100f;
                tvLV.setText((val - 200) + "%");
                StudioManager.setSkewLeftV(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewlv).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewLeftV");
                seekLV.setProgress(200);
                tvLV.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Left Skew (left-only, -200% to +200%, seekbar 0-400, center=200)
            SeekBar seekLO = v.findViewById(R.id.seek_skewlo); TextView tvLO = v.findViewById(R.id.tv_skewlo_val);
            float initLO = (float) effectiveTime.optDouble("skewLeftOnly", 0);
            seekLO.setProgress((int)(initLO * 100 + 200));
            tvLO.setText((int)(initLO * 100) + "%");
            seekLO.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 200) / 100f;
                tvLO.setText((val - 200) + "%");
                StudioManager.setSkewLeftOnly(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewlo).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewLeftOnly");
                seekLO.setProgress(200);
                tvLO.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Curvature (-100..+100 mapped to seekbar 0..200, center=100)
            SeekBar seekCurve = v.findViewById(R.id.seek_curve);
            TextView tvCurve = v.findViewById(R.id.tv_curve_val);
            float initCurve = (float) effectiveTime.optDouble("curvature", 0.0);
            int initCurvePct = Math.round(initCurve * 100f);
            initCurvePct = Math.max(-100, Math.min(100, initCurvePct));
            seekCurve.setMax(200);
            seekCurve.setProgress(initCurvePct + 100);
            tvCurve.setText(initCurvePct + "%");
            seekCurve.setOnSeekBarChangeListener(simple(val -> {
                int pct = val - 100;
                tvCurve.setText(pct + "%");
                StudioManager.setCurvature(requireContext(), pct / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_curve).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "curvature");
                seekCurve.setProgress(100);
                tvCurve.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            return v;
        }
    }

    // ── PAGE 5: Date ─────────────────────────────────────────────────────────
    public static class DatePage extends Fragment implements StudioFragment.OnStudioResetListener {

        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject d = st.getEffectiveDate();

            SwitchCompat swDate = getView().findViewById(R.id.sw_date_visible);
            View dateCtrl = getView().findViewById(R.id.layout_date_controls);
            boolean vis = d.optBoolean("visible", true);
            if (swDate != null) swDate.setChecked(vis);
            if (dateCtrl != null) dateCtrl.setVisibility(vis ? View.VISIBLE : View.GONE);

            SeekBar seekDs = getView().findViewById(R.id.seek_date_size);
            TextView tvDs = getView().findViewById(R.id.tv_date_size_val);
            int size = (int) d.optDouble("size", 40);
            if (seekDs != null) seekDs.setProgress(Math.min(200, Math.max(0, size)));
            if (tvDs != null) tvDs.setText(size + "sp");

            SeekBar seekDx = getView().findViewById(R.id.seek_date_x);
            TextView tvDx = getView().findViewById(R.id.tv_date_x_val);
            int x = (int) (d.optDouble("x", 0.5) * 100);
            if (seekDx != null) seekDx.setProgress(Math.min(100, Math.max(0, x)));
            if (tvDx != null) tvDx.setText(x + "%");

            SeekBar seekDy = getView().findViewById(R.id.seek_date_y);
            TextView tvDy = getView().findViewById(R.id.tv_date_y_val);
            int y = (int) (d.optDouble("y", 0.75) * 100);
            if (seekDy != null) seekDy.setProgress(Math.min(100, Math.max(0, y)));
            if (tvDy != null) tvDy.setText(y + "%");

            SeekBar seekDr = getView().findViewById(R.id.seek_date_rot);
            TextView tvDr = getView().findViewById(R.id.tv_date_rot_val);
            float rot = (float) d.optDouble("rotation", 0);
            if (seekDr != null) seekDr.setProgress((int) (rot + 180));
            if (tvDr != null) tvDr.setText((int) rot + "°");
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_date, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveDate = st.getEffectiveDate();

            SwitchCompat swDate = v.findViewById(R.id.sw_date_visible);
            View dateCtrl = v.findViewById(R.id.layout_date_controls);
            boolean initVis = effectiveDate.optBoolean("visible", true);
            swDate.setChecked(initVis); dateCtrl.setVisibility(initVis ? View.VISIBLE : View.GONE);
            swDate.setOnCheckedChangeListener((b2, ch) -> { dateCtrl.setVisibility(ch ? View.VISIBLE : View.GONE); StudioManager.setDateVisible(requireContext(), ch); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_date_visible).setOnClickListener(b -> { swDate.setChecked(true); StudioManager.resetDateKey(requireContext(), "visible"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekDs = v.findViewById(R.id.seek_date_size); TextView tvDs = v.findViewById(R.id.tv_date_size_val);
            int initDs = (int) effectiveDate.optDouble("size", 40); seekDs.setProgress(Math.min(200, Math.max(0, initDs))); tvDs.setText(initDs + "sp");
            seekDs.setOnSeekBarChangeListener(simple(val -> { tvDs.setText(val + "sp"); StudioManager.setDateFontSize(requireContext(), val); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_date_size).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "size"); seekDs.setProgress(40); tvDs.setText("40sp"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekDx = v.findViewById(R.id.seek_date_x); TextView tvDx = v.findViewById(R.id.tv_date_x_val);
            int initDx = (int)(effectiveDate.optDouble("x", 0.5) * 100); seekDx.setProgress(initDx); tvDx.setText(initDx + "%");
            seekDx.setOnSeekBarChangeListener(simple(val -> { tvDx.setText(val + "%"); StudioManager.setDatePosX(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_date_x).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "x"); seekDx.setProgress(50); tvDx.setText("50%"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekDy = v.findViewById(R.id.seek_date_y); TextView tvDy = v.findViewById(R.id.tv_date_y_val);
            int initDy = (int)(effectiveDate.optDouble("y", 0.75) * 100); seekDy.setProgress(initDy); tvDy.setText(initDy + "%");
            seekDy.setOnSeekBarChangeListener(simple(val -> { tvDy.setText(val + "%"); StudioManager.setDatePosY(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_date_y).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "y"); seekDy.setProgress(75); tvDy.setText("75%"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekDr = v.findViewById(R.id.seek_date_rot); TextView tvDr = v.findViewById(R.id.tv_date_rot_val);
            float initDr = (float) effectiveDate.optDouble("rotation", 0); seekDr.setProgress((int)(initDr + 180)); tvDr.setText((int) initDr + "°");
            seekDr.setOnSeekBarChangeListener(simple(val -> { float d = val - 180f; tvDr.setText((int)d + "°"); StudioManager.setDateRotation(requireContext(), d); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_date_rot).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "rotation"); seekDr.setProgress(180); tvDr.setText("0°"); st.scheduleRefresh(); st.broadcastChange(); });
            return v;
        }
    }

    // ── PAGE 6: Date Settings ────────────────────────────────────────────────
    public static class DateSettingsPage extends Fragment implements StudioFragment.OnStudioResetListener {

        @Override
        public void onResume() {
            super.onResume();
            StudioFragment.registerResetListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            StudioFragment.unregisterResetListener(this);
        }

        @Override
        public void onStudioReset() {
            if (!isAdded() || getView() == null) return;
            StudioFragment st = getStudio(this);
            if (st == null) return;
            JSONObject d = st.getEffectiveDate();

            // Format radio group
            RadioGroup rgFmt = getView().findViewById(R.id.rg_date_format);
            if (rgFmt != null) {
                String curFmt = d.optString("format", "EEE, dd MMM");
                if ("dd MMM yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mmm_yyyy);
                else if ("MMM dd".equals(curFmt)) rgFmt.check(R.id.rb_fmt_mmm_dd);
                else if ("dd/MM/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mm_yyyy);
                else if ("MM/dd/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_mm_dd_yyyy);
                else if ("EEEE".equals(curFmt)) rgFmt.check(R.id.rb_fmt_eeee);
                else if ("dd MMM".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mmm);
                else rgFmt.check(R.id.rb_fmt_eee_dd_mmm);
            }

            // Font picker selection
            RecyclerView rvDF = getView().findViewById(R.id.rv_date_fonts);
            if (rvDF != null && rvDF.getAdapter() instanceof FontPickerAdapter) {
                ((FontPickerAdapter) rvDF.getAdapter()).setSelected(d.optString("font", "main3.ttf"));
            }

            // Date color
            InlineColorPicker cpDate = getView().findViewById(R.id.color_picker_date);
            View swDC = getView().findViewById(R.id.swatch_date_color);
            String col = d.optString("color", "#FFFFFF");
            if (cpDate != null) cpDate.setSelectedColor(col);
            if (swDC != null) trySetBg(swDC, col);

            // All caps
            SwitchCompat swCaps = getView().findViewById(R.id.sw_date_allcaps);
            if (swCaps != null) swCaps.setChecked(d.optBoolean("allCaps", false));
        }

        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_date_settings, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveDate = st.getEffectiveDate();

            // Format radio group
            RadioGroup rgFmt = v.findViewById(R.id.rg_date_format);
            String curFmt = effectiveDate.optString("format", "EEE, dd MMM");
            if ("dd MMM yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mmm_yyyy);
            else if ("MMM dd".equals(curFmt)) rgFmt.check(R.id.rb_fmt_mmm_dd);
            else if ("dd/MM/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mm_yyyy);
            else if ("MM/dd/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_mm_dd_yyyy);
            else if ("EEEE".equals(curFmt)) rgFmt.check(R.id.rb_fmt_eeee);
            else if ("dd MMM".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mmm);
            else rgFmt.check(R.id.rb_fmt_eee_dd_mmm);
            rgFmt.setOnCheckedChangeListener((g, id) -> {
                String fmt = id == R.id.rb_fmt_dd_mmm_yyyy ? "dd MMM yyyy" : id == R.id.rb_fmt_mmm_dd ? "MMM dd" : id == R.id.rb_fmt_dd_mm_yyyy ? "dd/MM/yyyy" : id == R.id.rb_fmt_mm_dd_yyyy ? "MM/dd/yyyy" : id == R.id.rb_fmt_eeee ? "EEEE" : id == R.id.rb_fmt_dd_mmm ? "dd MMM" : "EEE, dd MMM";
                StudioManager.setDateFormat(requireContext(), fmt); st.scheduleRefresh(); st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_date_fmt).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "format"); rgFmt.check(R.id.rb_fmt_eee_dd_mmm); st.scheduleRefresh(); st.broadcastChange(); });

            RecyclerView rvDF = v.findViewById(R.id.rv_date_fonts);
            rvDF.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            FontPickerAdapter dfa = new FontPickerAdapter(requireContext(), Arrays.asList(FONTS), effectiveDate.optString("font", "main3.ttf"));
            dfa.setListener(font -> { StudioManager.setDateFont(requireContext(), font); st.scheduleRefresh(); st.broadcastChange(); });
            rvDF.setAdapter(dfa);
            v.findViewById(R.id.btn_reset_date_font).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "font"); dfa.setSelected("main3.ttf"); st.scheduleRefresh(); st.broadcastChange(); });

            // Date Settings Page - Date color
            String[] dc = { effectiveDate.optString("color", "#FFFFFF") };
            View swDC = v.findViewById(R.id.swatch_date_color);
            trySetBg(swDC, dc[0]);
            InlineColorPicker cpDate = v.findViewById(R.id.color_picker_date);
            if (cpDate != null) {
                cpDate.setSelectedColor(dc[0]);
                cpDate.setOnColorSelectedListener(hex -> {
                    dc[0] = hex;
                    trySetBg(swDC, hex);
                    StudioManager.setDateColor(requireContext(), hex);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            } else {
                // Fallback to dialog if layout not updated yet
                swDC.setOnClickListener(b -> ColorPickerDialog.show(requireContext(), dc[0], hex -> {
                    dc[0] = hex;
                    trySetBg(swDC, hex);
                    StudioManager.setDateColor(requireContext(), hex);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            v.findViewById(R.id.btn_reset_date_color).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "color");
                JSONObject base = st.getEffectiveDate();
                dc[0] = base.optString("color", "#FFFFFF");
                trySetBg(swDC, dc[0]);
                if (cpDate != null) cpDate.setSelectedColor(dc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            SwitchCompat swCaps = v.findViewById(R.id.sw_date_allcaps);
            swCaps.setChecked(effectiveDate.optBoolean("allCaps", false));
            swCaps.setOnCheckedChangeListener((b2, ch) -> { StudioManager.setDateAllCaps(requireContext(), ch); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_date_allcaps).setOnClickListener(b -> { swCaps.setChecked(false); StudioManager.resetDateKey(requireContext(), "allCaps"); st.scheduleRefresh(); st.broadcastChange(); });
            return v;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    interface IntConsumer { void accept(int v); }

    static SeekBar.OnSeekBarChangeListener simple(IntConsumer c) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) c.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    static void trySetBg(View view, String hex) {
        try { view.setBackgroundColor(android.graphics.Color.parseColor(hex)); } catch (Exception ignored) {}
    }
}



