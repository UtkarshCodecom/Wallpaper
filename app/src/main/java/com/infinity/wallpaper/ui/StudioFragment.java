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
import android.widget.SeekBar;import android.widget.TextView;
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
            Toast.makeText(requireContext(), "Studio reset to original", Toast.LENGTH_SHORT).show();
            requireActivity().getSupportFragmentManager()
                    .beginTransaction().detach(this).attach(this).commit();
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

    private void startSecondUpdaterIfNeeded() {
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
    public static class BasicsPage extends Fragment {
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
            v.findViewById(R.id.btn_reset_size).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "size"); seekSize.setProgress(520); tvSize.setText("520sp"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekX = v.findViewById(R.id.seek_posx); TextView tvX = v.findViewById(R.id.tv_posx_val);
            int initX = (int)(effectiveTime.optDouble("x", 0.5) * 100); seekX.setProgress(initX); tvX.setText(initX + "%");
            seekX.setOnSeekBarChangeListener(simple(val -> { tvX.setText(val + "%"); StudioManager.setPosX(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_posx).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "x"); seekX.setProgress(50); tvX.setText("50%"); st.scheduleRefresh(); st.broadcastChange(); });

            SeekBar seekY = v.findViewById(R.id.seek_posy); TextView tvY = v.findViewById(R.id.tv_posy_val);
            int initY = (int)(effectiveTime.optDouble("y", 0.65) * 100); seekY.setProgress(initY); tvY.setText(initY + "%");
            seekY.setOnSeekBarChangeListener(simple(val -> { tvY.setText(val + "%"); StudioManager.setPosY(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_posy).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "y"); seekY.setProgress(65); tvY.setText("65%"); st.scheduleRefresh(); st.broadcastChange(); });
            return v;
        }
    }

    // ── PAGE 2: Typography ───────────────────────────────────────────────────
    public static class TypographyPage extends Fragment {
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
            else                                 rgStyle.check(R.id.rb_style_hh_mm);
            // Clock style listener is set below after connector toggle setup

            // Depth
            RadioGroup rgDepth = v.findViewById(R.id.rg_depth);
            String curDepth = effectiveTime.optString("depthMode", "none");
            if ("hoursFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_hoursfront);
            else if ("minuteFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_minsfront);
            else rgDepth.check(R.id.rb_depth_standard);
            rgDepth.setOnCheckedChangeListener((g, id) -> { String dm = id == R.id.rb_depth_hoursfront ? "hoursFront" : id == R.id.rb_depth_minsfront ? "minuteFront" : "none"; StudioManager.setDepthMode(requireContext(), dm); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_depth).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "depthMode"); rgDepth.check(R.id.rb_depth_standard); st.scheduleRefresh(); st.broadcastChange(); });

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
                boolean hasSeconds = (checkedId == R.id.rb_style_hhmmss || checkedId == R.id.rb_style_hh_mm_ss_slash);
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
                rgStyle.check(R.id.rb_style_hh_mm);
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

            // Letter Spacing (seekbar 0-100 maps to -50 to +50)
            SeekBar seekLs = v.findViewById(R.id.seek_ls); TextView tvLs = v.findViewById(R.id.tv_ls_val);
            int initLs = (int) effectiveTime.optDouble("letterSpacing", 0);
            int seekVal = initLs + 50; // Convert -50..+50 to 0..100
            seekLs.setProgress(Math.min(100, Math.max(0, seekVal)));
            tvLs.setText(String.valueOf(initLs));
            seekLs.setOnSeekBarChangeListener(simple(val -> {
                int realVal = val - 50; // Convert 0..100 back to -50..+50
                tvLs.setText(String.valueOf(realVal));
                StudioManager.setLetterSpacing(requireContext(), realVal);
                st.scheduleRefresh(); st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_ls).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "letterSpacing"); seekLs.setProgress(50); tvLs.setText("0"); st.scheduleRefresh(); st.broadcastChange(); });

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

            // ...existing Typography controls (stroke, glow, etc.)...

            return v;
        }
    }

    // ── PAGE 3: Effects ──────────────────────────────────────────────────────
    public static class EffectsPage extends Fragment {
        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_effects, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveTime = st.getEffectiveTime();

            SeekBar seekOp = v.findViewById(R.id.seek_opacity); TextView tvOp = v.findViewById(R.id.tv_opacity_val);
            int initOp = (int)(effectiveTime.optDouble("opacity", 1.0) * 100); seekOp.setProgress(initOp); tvOp.setText(initOp + "%");
            seekOp.setOnSeekBarChangeListener(simple(val -> { tvOp.setText(val + "%"); StudioManager.setOpacity(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_opacity).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "opacity"); seekOp.setProgress(100); tvOp.setText("100%"); st.scheduleRefresh(); st.broadcastChange(); });

            // Mask Opacity
            SeekBar seekMaskOp = v.findViewById(R.id.seek_mask_opacity); TextView tvMaskOp = v.findViewById(R.id.tv_mask_opacity_val);
            int initMaskOp = (int)(effectiveTime.optDouble("maskOpacity", 1.0) * 100); seekMaskOp.setProgress(initMaskOp); tvMaskOp.setText(initMaskOp + "%");
            seekMaskOp.setOnSeekBarChangeListener(simple(val -> { tvMaskOp.setText(val + "%"); StudioManager.setMaskOpacity(requireContext(), val / 100f); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_mask_opacity).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "maskOpacity"); seekMaskOp.setProgress(100); tvMaskOp.setText("100%"); st.scheduleRefresh(); st.broadcastChange(); });

            SwitchCompat swShadow = v.findViewById(R.id.sw_shadow);
            View shadowCtrl = v.findViewById(R.id.layout_shadow_controls);
            boolean initShad = effectiveTime.optBoolean("shadowEnabled", false);
            swShadow.setChecked(initShad); shadowCtrl.setVisibility(initShad ? View.VISIBLE : View.GONE);
            swShadow.setOnCheckedChangeListener((b2, ch) -> { shadowCtrl.setVisibility(ch ? View.VISIBLE : View.GONE); StudioManager.setShadowEnabled(requireContext(), ch); st.scheduleRefresh(); st.broadcastChange(); });
            SeekBar seekSx = v.findViewById(R.id.seek_shadow_x); SeekBar seekSy = v.findViewById(R.id.seek_shadow_y);
            TextView tvSx = v.findViewById(R.id.tv_shadow_x_val); TextView tvSy = v.findViewById(R.id.tv_shadow_y_val);
            // Shadow X/Y: seekbar 0-200 maps to -100 to +100
            int initShadowX = (int)effectiveTime.optDouble("shadowX", 4);
            int initShadowY = (int)effectiveTime.optDouble("shadowY", 4);
            seekSx.setProgress(initShadowX + 100); tvSx.setText(String.valueOf(initShadowX));
            seekSy.setProgress(initShadowY + 100); tvSy.setText(String.valueOf(initShadowY));
            seekSx.setOnSeekBarChangeListener(simple(val -> { int realVal = val - 100; tvSx.setText(String.valueOf(realVal)); StudioManager.setShadowX(requireContext(), realVal); st.scheduleRefresh(); st.broadcastChange(); }));
            seekSy.setOnSeekBarChangeListener(simple(val -> { int realVal = val - 100; tvSy.setText(String.valueOf(realVal)); StudioManager.setShadowY(requireContext(), realVal); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_shadow).setOnClickListener(b -> { swShadow.setChecked(false); StudioManager.resetTimeKey(requireContext(), "shadowEnabled"); st.scheduleRefresh(); st.broadcastChange(); });
            // Reset buttons for shadow X and Y (default 4)
            v.findViewById(R.id.btn_reset_shadow_x).setOnClickListener(b -> { seekSx.setProgress(104); tvSx.setText("4"); StudioManager.setShadowX(requireContext(), 4); st.scheduleRefresh(); st.broadcastChange(); });
            v.findViewById(R.id.btn_reset_shadow_y).setOnClickListener(b -> { seekSy.setProgress(104); tvSy.setText("4"); StudioManager.setShadowY(requireContext(), 4); st.scheduleRefresh(); st.broadcastChange(); });

            SwitchCompat swStroke = v.findViewById(R.id.sw_stroke);
            View strokeCtrl = v.findViewById(R.id.layout_stroke_controls);
            boolean initStroke = effectiveTime.optBoolean("strokeEnabled", false);
            swStroke.setChecked(initStroke); strokeCtrl.setVisibility(initStroke ? View.VISIBLE : View.GONE);
            // Stroke toggle listener set below after swFill is defined

            // Stroke Target
            RadioGroup rgStrokeTarget = v.findViewById(R.id.rg_stroke_target);
            String curStrokeTgt = effectiveTime.optString("strokeTarget", "both");
            if ("hh".equals(curStrokeTgt)) rgStrokeTarget.check(R.id.rb_stroke_hh);
            else if ("mm".equals(curStrokeTgt)) rgStrokeTarget.check(R.id.rb_stroke_mm);
            else rgStrokeTarget.check(R.id.rb_stroke_both);
            rgStrokeTarget.setOnCheckedChangeListener((g, id) -> {
                String tgt = id == R.id.rb_stroke_hh ? "hh" : id == R.id.rb_stroke_mm ? "mm" : "both";
                StudioManager.setStrokeTarget(requireContext(), tgt); st.scheduleRefresh(); st.broadcastChange();
            });

            SeekBar seekSw = v.findViewById(R.id.seek_stroke_w); TextView tvSw = v.findViewById(R.id.tv_stroke_w_val);
            seekSw.setProgress((int)effectiveTime.optDouble("strokeWidth", 3)); tvSw.setText(String.valueOf(seekSw.getProgress()));
            seekSw.setOnSeekBarChangeListener(simple(val -> { tvSw.setText(String.valueOf(val)); StudioManager.setStrokeWidth(requireContext(), val); st.scheduleRefresh(); st.broadcastChange(); }));

            // Stroke Color - use inline color picker
            String[] sc = { effectiveTime.optString("strokeColor", "#000000") };
            View swSt = v.findViewById(R.id.swatch_stroke); trySetBg(swSt, sc[0]);
            InlineColorPicker cpStroke = v.findViewById(R.id.color_picker_stroke);
            cpStroke.setSelectedColor(sc[0]);
            cpStroke.setOnColorSelectedListener(hex -> { sc[0] = hex; trySetBg(swSt, hex); StudioManager.setStrokeColor(requireContext(), hex); st.scheduleRefresh(); st.broadcastChange(); });

            // Fill vs Stroke toggle
            SwitchCompat swFill = v.findViewById(R.id.sw_fill_enabled);
            boolean initFill = effectiveTime.optBoolean("fillEnabled", true);
            swFill.setChecked(initFill);
            swFill.setOnCheckedChangeListener((b2, ch) -> {
                StudioManager.setFillEnabled(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Update stroke toggle to auto-enable fill when stroke is disabled
            swStroke.setOnCheckedChangeListener((b2, ch) -> {
                strokeCtrl.setVisibility(ch ? View.VISIBLE : View.GONE);
                StudioManager.setStrokeEnabled(requireContext(), ch);
                // Auto-enable fill when stroke is disabled to prevent invisible text
                if (!ch) {
                    swFill.setChecked(true);
                    StudioManager.setFillEnabled(requireContext(), true);
                }
                st.scheduleRefresh();
                st.broadcastChange();
            });

            v.findViewById(R.id.btn_reset_stroke).setOnClickListener(b -> {
                swStroke.setChecked(false);
                StudioManager.resetTimeKey(requireContext(), "strokeEnabled");
                StudioManager.resetTimeKey(requireContext(), "strokeTarget");
                StudioManager.resetTimeKey(requireContext(), "strokeColor");
                JSONObject base = st.getEffectiveTime();
                sc[0] = base.optString("strokeColor", "#000000");
                trySetBg(swSt, sc[0]);
                cpStroke.setSelectedColor(sc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Glow radius + color
            SeekBar seekGlow = v.findViewById(R.id.seek_glow_radius);
            TextView tvGlow = v.findViewById(R.id.tv_glow_radius_val);
            int initGlow = (int) effectiveTime.optDouble("glowRadius", 0);
            seekGlow.setProgress(initGlow);
            tvGlow.setText(String.valueOf(initGlow));
            seekGlow.setOnSeekBarChangeListener(simple(val -> {
                tvGlow.setText(String.valueOf(val));
                StudioManager.setGlowRadius(requireContext(), val);
                st.scheduleRefresh();
                st.broadcastChange();
            }));

            // glow color (defaults to hour color if not set) - use inline color picker
            String glowColorInit = effectiveTime.optString("glowColor", "");
            if (glowColorInit == null || glowColorInit.isEmpty()) {
                glowColorInit = effectiveTime.optString("hourColor", "#FFFFFF");
            }
            String[] gc = { glowColorInit };
            View swGlow = v.findViewById(R.id.swatch_glow);
            trySetBg(swGlow, gc[0]);
            InlineColorPicker cpGlow = v.findViewById(R.id.color_picker_glow);
            cpGlow.setSelectedColor(gc[0]);
            cpGlow.setOnColorSelectedListener(hex -> {
                gc[0] = hex;
                trySetBg(swGlow, hex);
                StudioManager.setGlowColor(requireContext(), hex);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            v.findViewById(R.id.btn_reset_glow).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "glowRadius");
                StudioManager.resetTimeKey(requireContext(), "glowColor");
                seekGlow.setProgress(0);
                tvGlow.setText("0");
                // reset swatch to hour color
                JSONObject effective = st.getEffectiveTime();
                String hc = effective.optString("hourColor", "#FFFFFF");
                gc[0] = hc;
                trySetBg(swGlow, hc);
                cpGlow.setSelectedColor(hc);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            return v;
        }
    }

    // ── PAGE 4: Transform ────────────────────────────────────────────────────
    public static class TransformPage extends Fragment {
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
            v.findViewById(R.id.btn_reset_rot).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "rotation"); seekRot.setProgress(180); tvRot.setText("0°"); st.scheduleRefresh(); st.broadcastChange(); });

            // Stretch X (0-400 → 0%-400%)
            SeekBar seekSx = v.findViewById(R.id.seek_sx); TextView tvSx = v.findViewById(R.id.tv_sx_val);
            float initSx = (float) effectiveTime.optDouble("stretchX", 1.0);
            int initSxPct = (int) (initSx * 100);
            seekSx.setProgress(Math.min(400, Math.max(0, initSxPct)));
            tvSx.setText(Math.min(400, Math.max(0, initSxPct)) + "%");
            seekSx.setOnSeekBarChangeListener(simple(val -> { float sv = val / 100f; tvSx.setText(val + "%"); StudioManager.setStretchX(requireContext(), sv); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_sx).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "stretchX"); seekSx.setProgress(100); tvSx.setText("100%"); st.scheduleRefresh(); st.broadcastChange(); });

            // Stretch Y
            SeekBar seekSy = v.findViewById(R.id.seek_sy); TextView tvSy = v.findViewById(R.id.tv_sy_val);
            float initSy = (float) effectiveTime.optDouble("stretchY", 1.0);
            int initSyPct = (int) (initSy * 100);
            seekSy.setProgress(Math.min(400, Math.max(0, initSyPct)));
            tvSy.setText(Math.min(400, Math.max(0, initSyPct)) + "%");
            seekSy.setOnSeekBarChangeListener(simple(val -> { float sv = val / 100f; tvSy.setText(val + "%"); StudioManager.setStretchY(requireContext(), sv); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_sy).setOnClickListener(b -> { StudioManager.resetTimeKey(requireContext(), "stretchY"); seekSy.setProgress(100); tvSy.setText("100%"); st.scheduleRefresh(); st.broadcastChange(); });

            // Skew H (-100% to +100%, seekbar 0-200, center=100)
            SeekBar seekSH = v.findViewById(R.id.seek_skewh); TextView tvSH = v.findViewById(R.id.tv_skewh_val);
            float initSH = (float) effectiveTime.optDouble("skewH", 0);
            seekSH.setProgress((int)(initSH * 100 + 100));
            tvSH.setText((int)(initSH * 100) + "%");
            seekSH.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 100) / 100f;
                tvSH.setText((val - 100) + "%");
                StudioManager.setSkewH(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewh).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewH");
                seekSH.setProgress(100);
                tvSH.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Skew V (-100% to +100%, seekbar 0-200, center=100)
            SeekBar seekSV = v.findViewById(R.id.seek_skewv); TextView tvSV = v.findViewById(R.id.tv_skewv_val);
            float initSV = (float) effectiveTime.optDouble("skewV", 0);
            seekSV.setProgress((int)(initSV * 100 + 100));
            tvSV.setText((int)(initSV * 100) + "%");
            seekSV.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 100) / 100f;
                tvSV.setText((val - 100) + "%");
                StudioManager.setSkewV(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewv).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewV");
                seekSV.setProgress(100);
                tvSV.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Bottom Skew H (-100% to +100%, seekbar 0-200, center=100)
            SeekBar seekBH = v.findViewById(R.id.seek_skewbh); TextView tvBH = v.findViewById(R.id.tv_skewbh_val);
            float initBH = (float) effectiveTime.optDouble("skewBottomH", 0);
            seekBH.setProgress((int)(initBH * 100 + 100));
            tvBH.setText((int)(initBH * 100) + "%");
            seekBH.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 100) / 100f;
                tvBH.setText((val - 100) + "%");
                StudioManager.setSkewBottomH(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewbh).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewBottomH");
                seekBH.setProgress(100);
                tvBH.setText("0%");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Left Skew V (-100% to +100%, seekbar 0-200, center=100)
            SeekBar seekLV = v.findViewById(R.id.seek_skewlv); TextView tvLV = v.findViewById(R.id.tv_skewlv_val);
            float initLV = (float) effectiveTime.optDouble("skewLeftV", 0);
            seekLV.setProgress((int)(initLV * 100 + 100));
            tvLV.setText((int)(initLV * 100) + "%");
            seekLV.setOnSeekBarChangeListener(simple(val -> {
                float sk = (val - 100) / 100f;
                tvLV.setText((val - 100) + "%");
                StudioManager.setSkewLeftV(requireContext(), sk);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_skewlv).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "skewLeftV");
                seekLV.setProgress(100);
                tvLV.setText("0%");
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
    public static class DatePage extends Fragment {
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
            float initDr = (float) effectiveDate.optDouble("rotation", 0); seekDr.setProgress((int)(initDr + 180)); tvDr.setText((int)initDr + "°");
            seekDr.setOnSeekBarChangeListener(simple(val -> { float d = val - 180f; tvDr.setText((int)d + "°"); StudioManager.setDateRotation(requireContext(), d); st.scheduleRefresh(); st.broadcastChange(); }));
            v.findViewById(R.id.btn_reset_date_rot).setOnClickListener(b -> { StudioManager.resetDateKey(requireContext(), "rotation"); seekDr.setProgress(180); tvDr.setText("0°"); st.scheduleRefresh(); st.broadcastChange(); });
            return v;
        }
    }

    // ── PAGE 6: Date Settings ────────────────────────────────────────────────
    public static class DateSettingsPage extends Fragment {
        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_date_settings, c, false);
            StudioFragment st = getStudio(this); if (st == null) return v;

            // Read from effective (merged) theme for initial values
            JSONObject effectiveDate = st.getEffectiveDate();

            RadioGroup rgFmt = v.findViewById(R.id.rg_date_format);
            String curFmt = effectiveDate.optString("format", "EEE, dd MMM");
            if ("dd MMM yyyy".equals(curFmt))     rgFmt.check(R.id.rb_fmt_dd_mmm_yyyy);
            else if ("MMM dd".equals(curFmt))     rgFmt.check(R.id.rb_fmt_mmm_dd);
            else if ("dd/MM/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_dd_mm_yyyy);
            else if ("MM/dd/yyyy".equals(curFmt)) rgFmt.check(R.id.rb_fmt_mm_dd_yyyy);
            else if ("EEEE".equals(curFmt))       rgFmt.check(R.id.rb_fmt_eeee);
            else if ("dd MMM".equals(curFmt))     rgFmt.check(R.id.rb_fmt_dd_mmm);
            else                                  rgFmt.check(R.id.rb_fmt_eee_dd_mmm);
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
                // Swatch becomes purely a preview (no dialog)
                swDC.setOnClickListener(null);
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
