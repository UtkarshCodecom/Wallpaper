package com.walle.wallpaper.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.walle.wallpaper.R;
import com.walle.wallpaper.render.ThemeRenderer;
import com.walle.wallpaper.ui.common.InlineColorPicker;
import com.walle.wallpaper.util.SettingsManager;
import com.walle.wallpaper.util.StudioManager;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;

public class StudioFragment extends Fragment {
    /**
     * Combined Cloudflare fonts cache
     */
    public static final java.util.List<FontPickerAdapter.FontItem> loadedFontsList = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    static final java.util.concurrent.CopyOnWriteArrayList<OnStudioResetListener> resetListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final String[] TAB_NAMES = {"Core", "Tag", "FX", "Alignment", "Date", "Customize"};
    private static final int REQ_PICK_CUSTOM_BG = 1122;
    // Render the preview at a fraction of the canonical canvas — the preview view is small,
    // so this is visually identical but far cheaper to render/upload, giving smooth dragging.
    private static final float PREVIEW_SCALE = 0.5f;
    private static boolean fontsFetched = false;
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService fontDownloadExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);
    // Per-second updater when seconds style is selected
    private final Handler secondHandler = new Handler(Looper.getMainLooper());
    private ImageView ivBg, ivText, ivMask;
    private ProgressBar pbPreview;
    private Runnable pendingRefresh;
    private Runnable secondRunnable = null;
    private boolean secondsTickerRunning = false;
    private final java.util.concurrent.ExecutorService renderExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int renderGeneration = 0;

    // ── Direct manipulation (drag to move, pinch to scale, twist to rotate) ──
    private static final int ELEM_NONE = 0, ELEM_TIME = 1, ELEM_DATE = 2;
    private int dragElement = ELEM_NONE;
    private float dragDownX, dragDownY;              // finger px at gesture start
    private float dragStartNormX, dragStartNormY;    // element normalized pos at start
    private float pinchStartSize;                    // element size at pinch start
    private float pinchStartDist;
    private float pinchStartAngle;                   // angle between fingers at pinch start
    private float pinchStartRotation;                // element rotation at pinch start
    private boolean pinching;

    // Cached decoded+scaled bg/mask so a drag doesn't re-read the wallpaper from disk each frame.
    private Bitmap previewBg, previewMask;
    private String previewCacheKey;
    private ThemeRenderer previewRenderer;

    static void registerResetListener(OnStudioResetListener l) {
        if (l != null && !resetListeners.contains(l)) resetListeners.add(l);
    }

    static void unregisterResetListener(OnStudioResetListener l) {
        if (l != null) resetListeners.remove(l);
    }

    static StudioFragment getStudio(Fragment child) {
        Fragment p = child.getParentFragment();
        if (p instanceof StudioFragment) return (StudioFragment) p;
        if (p != null && p.getParentFragment() instanceof StudioFragment)
            return (StudioFragment) p.getParentFragment();
        if (child.getActivity() != null)
            for (Fragment f : child.getActivity().getSupportFragmentManager().getFragments())
                if (f instanceof StudioFragment) return (StudioFragment) f;
        return null;
    }

    /**
     * Nudges a SeekBar by delta, updates the label TextView, calls the StudioManager setter,
     * and triggers a preview refresh + wallpaper broadcast.
     */
    static void nudgeSeekAndApply(@Nullable SeekBar seek, int delta,
                                  @Nullable TextView label, @NonNull String labelFormat,
                                  @NonNull Runnable studioManagerCall,
                                  @NonNull StudioFragment st) {
        if (seek == null) return;
        int next = Math.max(0, Math.min(seek.getMax(), seek.getProgress() + delta));
        seek.setProgress(next);
        if (label != null) {
            // labelFormat uses printf style: e.g. "%dsp", "%d%%", "%d°"
            label.setText(String.format(labelFormat, next));
        }
        studioManagerCall.run();
        st.scheduleRefresh();
        st.broadcastChange();
    }

    static SeekBar.OnSeekBarChangeListener simple(IntConsumer c) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u) c.accept(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        };
    }

    static void trySetBg(View view, String hex) {
        try {
            view.setBackgroundColor(android.graphics.Color.parseColor(hex));
        } catch (Exception ignored) {
        }
    }

    private void notifyStudioReset() {
        for (OnStudioResetListener l : resetListeners) {
            try {
                l.onStudioReset();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Shows a fast save action whenever Studio was opened from Admin — for both a NEW
     * wallpaper ("Upload Wallpaper") and an existing one being edited ("Update Wallpaper").
     * Tapping it pops straight back to Admin, which finishes the save immediately instead
     * of requiring an extra manual tap on Admin's own Upload button.
     */
    private void setupQuickUpdateButton(View root) {
        View btn = root.findViewById(R.id.btn_update_wallpaper);
        if (btn == null) return;

        boolean fromAdmin = false;
        boolean editingExisting = false;
        try {
            String pending = requireContext().getSharedPreferences("wallpaper_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("admin_pending", "");
            if (!pending.isEmpty()) {
                // Any pending-admin state means Admin sent us here (it saves the form before
                // navigating). An "editId" additionally means an existing wallpaper.
                fromAdmin = true;
                editingExisting = !new JSONObject(pending).optString("editId", "").isEmpty();
            }
        } catch (Exception ignored) {
        }

        if (!fromAdmin) return;
        if (btn instanceof TextView) {
            ((TextView) btn).setText(editingExisting ? "⚡ Update Wallpaper" : "⚡ Upload Wallpaper");
        }
        btn.setVisibility(View.VISIBLE);
        btn.setOnClickListener(v -> {
            AdminFragment.requestQuickUpdateOnReturn();
            requireActivity().getSupportFragmentManager().popBackStack();
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_studio, container, false);
        ivBg = root.findViewById(R.id.studio_preview_bg);
        ivText = root.findViewById(R.id.studio_preview_text);
        ivMask = root.findViewById(R.id.studio_preview_mask);
        pbPreview = root.findViewById(R.id.studio_preview_progress);

        // Match the preview to THIS device's aspect ratio (same canvas the live wallpaper
        // renders to), so what you align here is what the device shows — WYSIWYG.
        View previewFrame = root.findViewById(R.id.studio_preview_frame);
        previewFrame.post(() -> {
            int h = previewFrame.getHeight();
            if (h > 0) {
                int rh = canonicalHeightForDevice();
                int w = Math.round(h * (float) ThemeRenderer.REF_WIDTH / rh);
                ViewGroup.LayoutParams lp = previewFrame.getLayoutParams();
                lp.width = w;
                previewFrame.setLayoutParams(lp);
            }
        });

        // Touch to move / pinch to scale the time & date directly on the preview.
        // Attach to the preview ImageView itself (a leaf view) so it reliably captures touches.
        setupPreviewTouch(ivText);

        loadPreviewImages();

        root.findViewById(R.id.btn_studio_reset).setOnClickListener(v -> {
            StudioManager.clearAll(requireContext());
            clearCustomBackground();
            broadcastChange();
            refreshPreview();
            startSecondUpdaterIfNeeded();
            notifyStudioReset();
            Toast.makeText(requireContext(), "Studio reset to original", Toast.LENGTH_SHORT).show();
        });

        setupQuickUpdateButton(root);

        ViewPager2 pager = root.findViewById(R.id.studio_viewpager);
        pager.setAdapter(new StudioPagerAdapter(this));
        pager.setOffscreenPageLimit(6);
        TabLayout tabs = root.findViewById(R.id.studio_tabs);
        new TabLayoutMediator(tabs, pager, (tab, pos) -> tab.setText(TAB_NAMES[pos])).attach();

        // Show the user's own fonts immediately (they're local — no network needed), then
        // let the Firestore fetch merge the admin fonts in when it lands.
        rebuildFontList(requireContext());

        if (!fontsFetched) {
            fetchCustomFonts();
            fontsFetched = true;
        }

        return root;
    }

    private void fetchCustomFonts() {
        // Cache-first for instant paint, then ALWAYS refresh from the server so a font added
        // by the admin shows up on the next open instead of waiting for Firestore's cached
        // copy to expire on its own.
        com.walle.wallpaper.util.FirestoreCacheFirst.load(
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("fonts"),
                null,
                snap -> {
                    if (!isAdded()) return;
                    java.util.List<FontPickerAdapter.FontItem> customFonts = new java.util.ArrayList<>();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : snap) {
                        String nickname = doc.getString("nickname");
                        String url = doc.getString("url");
                        if (nickname != null && url != null) {
                            String fontId = doc.getId();
                            if (!fontId.endsWith(".ttf") && !fontId.endsWith(".otf")) {
                                fontId += url.contains(".otf") ? ".otf" : ".ttf";
                            }

                            customFonts.add(new FontPickerAdapter.FontItem(fontId, nickname, true));

                            java.io.File cf = new java.io.File(new java.io.File(requireContext().getFilesDir(), "custom_fonts"), fontId);
                            if (!cf.exists()) {
                                cf.getParentFile().mkdirs();
                                final String finalFontId = fontId;
                                final android.content.Context appCtx = requireContext().getApplicationContext();
                                fontDownloadExecutor.execute(() -> {
                                    try {
                                        new com.walle.wallpaper.util.DownloadWithProgress().download(url, cf, null);
                                        // Drop any cached fallback typeface and redraw the
                                        // live wallpaper + preview with the real font.
                                        ThemeRenderer.invalidateFontCache(finalFontId);
                                        android.content.Intent notify =
                                                new android.content.Intent(SettingsManager.ACTION_SETTINGS_CHANGED);
                                        notify.setPackage(appCtx.getPackageName());
                                        appCtx.sendBroadcast(notify);
                                        if (isAdded())
                                            requireActivity().runOnUiThread(this::notifyFontListReady);
                                    } catch (Exception ignored) {
                                    }
                                });
                            }
                        }
                    }
                    adminFonts.clear();
                    adminFonts.addAll(customFonts);
                    rebuildFontList(requireContext());
                    notifyFontListReady();
                });
    }

    // Admin fonts from Firestore, kept separately so the user's own fonts survive a refresh.
    private static final java.util.List<FontPickerAdapter.FontItem> adminFonts =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Rebuilds the shared picker list as (admin fonts + this device's user-added fonts).
     * Safe to call at any time — it never drops user fonts just because Firestore is empty
     * or offline.
     */
    public static void rebuildFontList(android.content.Context ctx) {
        java.util.List<FontPickerAdapter.FontItem> merged = new java.util.ArrayList<>();
        synchronized (adminFonts) {
            merged.addAll(adminFonts);
        }
        for (com.walle.wallpaper.util.UserFontStore.Entry e :
                com.walle.wallpaper.util.UserFontStore.list(ctx)) {
            merged.add(new FontPickerAdapter.FontItem(e.id, e.name, true));
        }
        merged.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        synchronized (loadedFontsList) {
            loadedFontsList.clear();
            loadedFontsList.addAll(merged);
        }
    }

    public void notifyFontListReady() {
        if (!isAdded()) return;
        for (Fragment f : getChildFragmentManager().getFragments()) {
            if (f != null && f.getView() != null) {
                RecyclerView rvFonts = f.getView().findViewById(R.id.rv_fonts);
                if (rvFonts != null && rvFonts.getAdapter() instanceof FontPickerAdapter) {
                    rvFonts.getAdapter().notifyDataSetChanged();
                }
                RecyclerView rvDF = f.getView().findViewById(R.id.rv_date_fonts);
                if (rvDF != null && rvDF.getAdapter() instanceof FontPickerAdapter) {
                    rvDF.getAdapter().notifyDataSetChanged();
                }
            }
        }
    }

    private void loadPreviewImages() {
        File dir = new File(requireContext().getFilesDir(), "wallpaper");
        File customBgFile = new File(requireContext().getFilesDir(), "custom_bg.png");
        File bgFile = new File(dir, "bg.png");
        if (!bgFile.exists()) bgFile = new File(dir, "bg.jpg");

        if (!customBgFile.exists() && !bgFile.exists()) {
            pbPreview.setVisibility(View.VISIBLE);
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("wallpapers")
                    .get()
                    .addOnCompleteListener(task -> {
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                                java.util.List<com.google.firebase.firestore.QueryDocumentSnapshot> docs = new java.util.ArrayList<>();
                                for (com.google.firebase.firestore.QueryDocumentSnapshot d : task.getResult()) docs.add(d);
                                docs.sort((a, b) -> {
                                    long cTimeA = 0, cTimeB = 0;
                                    Object objA = a.get("createdAt");
                                    if (objA instanceof com.google.firebase.Timestamp) cTimeA = ((com.google.firebase.Timestamp) objA).toDate().getTime();
                                    else if (objA instanceof Number) cTimeA = ((Number) objA).longValue();
                                    
                                    Object objB = b.get("createdAt");
                                    if (objB instanceof com.google.firebase.Timestamp) cTimeB = ((com.google.firebase.Timestamp) objB).toDate().getTime();
                                    else if (objB instanceof Number) cTimeB = ((Number) objB).longValue();
                                    
                                    return Long.compare(cTimeB, cTimeA);
                                });
                                com.google.firebase.firestore.DocumentSnapshot doc = docs.get(0);
                                com.walle.wallpaper.data.WallpaperItem item = doc.toObject(com.walle.wallpaper.data.WallpaperItem.class);
                                if (item != null) {
                                    item.id = doc.getId();
                                    String bg = item.bgUrl != null && !item.bgUrl.isEmpty() ? item.bgUrl : item.previewUrl;
                                    String mask = item.maskUrl;
                                    Object themeObj = null;
                                    if (item.themes != null) {
                                        for (java.util.Map.Entry<String, Object> e : item.themes.entrySet()) {
                                            themeObj = e.getValue();
                                            break;
                                        }
                                    }
                                    if (bg != null && !bg.isEmpty()) {
                                        com.walle.wallpaper.WallpaperApplier.prefetch(requireContext(), bg, mask, themeObj, pct -> {}, (success, err) -> {
                                            if (isAdded() && success) {
                                                com.walle.wallpaper.util.SelectedWallpaperStore.setSelected(requireContext(), item);
                                                requireActivity().runOnUiThread(() -> {
                                                    pbPreview.setVisibility(View.GONE);
                                                    refreshPreview();
                                                });
                                            } else if (isAdded()) {
                                                requireActivity().runOnUiThread(() -> pbPreview.setVisibility(View.GONE));
                                            }
                                        });
                                    } else {
                                        pbPreview.setVisibility(View.GONE);
                                    }
                                } else {
                                    pbPreview.setVisibility(View.GONE);
                                }
                            } else {
                                pbPreview.setVisibility(View.GONE);
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        if (isAdded()) pbPreview.setVisibility(View.GONE);
                    });
            return;
        }

        refreshPreview();
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
        // Update actual wallpaper system when user leaves the Studio view
        notifyWallpaperService();
    }



    private Bitmap tryLoad(File f) {
        try {
            return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void scheduleRefresh() {
        debounce.removeCallbacks(pendingRefresh);
        pendingRefresh = this::refreshPreview;
        debounce.post(pendingRefresh);
    }

    /** Full-screen pixel size {width, height} of this device. */
    private int[] deviceScreenSize() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect b = requireActivity().getWindowManager()
                        .getMaximumWindowMetrics().getBounds();
                if (b.width() > 0 && b.height() > 0) return new int[]{b.width(), b.height()};
            }
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            requireActivity().getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Exception ignored) {
        }
        return new int[]{1080, 2400};
    }

    /**
     * Canonical render height for this device — the exact mirror of the wallpaper service's
     * {@code canonicalHeightFor}. Width is always REF_WIDTH; height follows the device aspect,
     * clamped to [16:9 .. 9:21]. Keeping this identical to the service is what makes the editor
     * WYSIWYG for the device it runs on.
     */
    public int canonicalHeightForDevice() {
        int[] s = deviceScreenSize();
        long h = Math.round((double) ThemeRenderer.REF_WIDTH * s[1] / s[0]);
        long min = Math.round(ThemeRenderer.REF_WIDTH * 16.0 / 9.0);
        long max = Math.round(ThemeRenderer.REF_WIDTH * 21.0 / 9.0);
        return (int) Math.max(min, Math.min(max, h));
    }

    public void refreshPreview() {
        if (!isAdded()) return;
        final int myGen = ++renderGeneration; // newer calls invalidate older ones
        final android.content.Context ctx = requireContext();

        renderExecutor.execute(() -> {
            if (myGen != renderGeneration) return; // stale, skip work
            try {
                // Theme merge + render all happen off the main thread.
                String themeJson = StudioManager.getEffectiveThemeJson(ctx);
                int w = Math.round(ThemeRenderer.REF_WIDTH * PREVIEW_SCALE);
                int h = Math.round(canonicalHeightForDevice() * PREVIEW_SCALE);
                String scaledTheme = scaleThemeForPreview(themeJson, PREVIEW_SCALE);
                Bitmap composed = composePreview(ctx, scaledTheme, w, h);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded() || myGen != renderGeneration) {
                        if (composed != null) composed.recycle();
                        return;
                    }
                    if (composed != null) {
                        ivBg.setVisibility(View.GONE);
                        ivMask.setVisibility(View.GONE);
                        android.graphics.drawable.Drawable old = ivText.getDrawable();
                        ivText.setImageBitmap(composed);
                        ivText.setVisibility(View.VISIBLE);
                        // Free the previous frame's bitmap to avoid piling up during a drag.
                        if (old instanceof android.graphics.drawable.BitmapDrawable) {
                            Bitmap ob = ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                            if (ob != null && ob != composed && !ob.isRecycled()) ob.recycle();
                        }
                    }
                    pbPreview.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isAdded()) pbPreview.setVisibility(View.GONE);
                });
            }
        });
    }

    /**
     * Returns a copy of the theme JSON with absolute-pixel fields (font/date sizes, letter
     * spacing, shadow offsets, stroke width, glow radius) scaled by {@code f}. Positions are
     * fractional so they're already resolution-independent; scaling the pixel fields lets us
     * render the preview at a lower resolution while keeping identical proportions.
     */
    private static String scaleThemeForPreview(String themeJson, float f) {
        if (Math.abs(f - 1f) < 0.001f) return themeJson;
        try {
            JSONObject root = new JSONObject(themeJson);
            scaleGroupAbsolutes(root.optJSONObject("time"), f);
            scaleGroupAbsolutes(root.optJSONObject("date"), f);
            return root.toString();
        } catch (Exception e) {
            return themeJson;
        }
    }

    private static void scaleGroupAbsolutes(@Nullable JSONObject o, float f) {
        if (o == null) return;
        String[] keys = {"size", "letterSpacing", "shadowX", "shadowY", "strokeWidth", "glowRadius"};
        for (String k : keys) {
            if (o.has(k)) {
                try {
                    o.put(k, o.getDouble(k) * f);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ── Direct manipulation: drag the time/date, pinch to scale ─────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupPreviewTouch(View preview) {
        preview.setOnTouchListener((v, ev) -> {
            int w = v.getWidth(), h = v.getHeight();
            if (w == 0 || h == 0) return false;

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    dragElement = pickElement(ev.getX() / w, ev.getY() / h);
                    dragDownX = ev.getX();
                    dragDownY = ev.getY();
                    float[] pos = elementPos(dragElement);
                    dragStartNormX = pos[0];
                    dragStartNormY = pos[1];
                    pinching = false;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (ev.getPointerCount() >= 2) {
                        pinching = true;
                        pinchStartDist = spacing(ev);
                        pinchStartSize = elementSize(dragElement);
                        pinchStartAngle = angle(ev);
                        pinchStartRotation = elementRotation(dragElement);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragElement == ELEM_NONE) return true;
                    if (pinching && ev.getPointerCount() >= 2) {
                        float d = spacing(ev);
                        if (pinchStartDist > 0 && d > 0) {
                            applyElementSize(dragElement, pinchStartSize * (d / pinchStartDist));
                            // Twist to rotate: change in finger angle → element rotation.
                            float delta = angle(ev) - pinchStartAngle;
                            while (delta > 180f) delta -= 360f;
                            while (delta < -180f) delta += 360f;
                            applyElementRotation(dragElement, pinchStartRotation + delta);
                            scheduleRefresh();
                        }
                    } else if (!pinching) {
                        float nx = clamp01(dragStartNormX + (ev.getX() - dragDownX) / w);
                        float ny = clamp01(dragStartNormY + (ev.getY() - dragDownY) / h);
                        applyElementPos(dragElement, nx, ny);
                        scheduleRefresh();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    // Back to one finger: end pinch and rebase the drag to the finger that stays.
                    pinching = false;
                    int remaining = ev.getActionIndex() == 0 ? 1 : 0;
                    dragDownX = ev.getX(remaining);
                    dragDownY = ev.getY(remaining);
                    float[] pos = elementPos(dragElement);
                    dragStartNormX = pos[0];
                    dragStartNormY = pos[1];
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    pinching = false;
                    if (dragElement != ELEM_NONE) {
                        scheduleRefresh();   // guarantee a final preview frame
                        broadcastChange();   // push the final edit to the live wallpaper
                        notifyStudioReset(); // sync the editor sliders/values to the new state
                    }
                    dragElement = ELEM_NONE;
                    return true;
                }
            }
            return false;
        });
    }

    private static float spacing(MotionEvent ev) {
        if (ev.getPointerCount() < 2) return 0f;
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    /** Angle (degrees) of the line between the first two fingers. */
    private static float angle(MotionEvent ev) {
        if (ev.getPointerCount() < 2) return 0f;
        float dx = ev.getX(1) - ev.getX(0);
        float dy = ev.getY(1) - ev.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private JSONObject effGroup(String group) {
        try {
            return new JSONObject(StudioManager.getEffectiveThemeJson(requireContext())).optJSONObject(group);
        } catch (Exception e) {
            return null;
        }
    }

    /** Choose the element (time vs date) whose anchor is nearest the touch. */
    private int pickElement(float nx, float ny) {
        JSONObject time = effGroup("time");
        JSONObject date = effGroup("date");
        float tx = time != null ? (float) time.optDouble("x", 0.5) : 0.5f;
        float ty = time != null ? (float) time.optDouble("y", 0.65) : 0.65f;
        double dTime = Math.hypot(nx - tx, ny - ty);

        boolean dateVisible = date != null && date.optBoolean("visible", true);
        if (!dateVisible) return ELEM_TIME;
        float dx = (float) date.optDouble("x", 0.5);
        float dy = (float) date.optDouble("y", 0.75);
        double dDate = Math.hypot(nx - dx, ny - dy);
        return dDate < dTime ? ELEM_DATE : ELEM_TIME;
    }

    private float[] elementPos(int elem) {
        JSONObject o = effGroup(elem == ELEM_DATE ? "date" : "time");
        float defY = elem == ELEM_DATE ? 0.75f : 0.65f;
        float x = o != null ? (float) o.optDouble("x", 0.5) : 0.5f;
        float y = o != null ? (float) o.optDouble("y", defY) : defY;
        return new float[]{x, y};
    }

    private float elementSize(int elem) {
        JSONObject o = effGroup(elem == ELEM_DATE ? "date" : "time");
        if (elem == ELEM_DATE) {
            float def = Math.max(24f, ThemeRenderer.REF_WIDTH / 20f);
            return o != null ? (float) o.optDouble("size", def) : def;
        }
        return o != null ? (float) o.optDouble("size", 520) : 520f;
    }

    private void applyElementPos(int elem, float nx, float ny) {
        if (elem == ELEM_DATE) {
            StudioManager.setDatePosX(requireContext(), nx);
            StudioManager.setDatePosY(requireContext(), ny);
        } else {
            StudioManager.setPosX(requireContext(), nx);
            StudioManager.setPosY(requireContext(), ny);
        }
    }

    private void applyElementSize(int elem, float size) {
        if (elem == ELEM_DATE) {
            StudioManager.setDateFontSize(requireContext(), Math.max(12f, Math.min(600f, size)));
        } else {
            StudioManager.setFontSize(requireContext(), Math.max(40f, Math.min(1600f, size)));
        }
    }

    private float elementRotation(int elem) {
        JSONObject o = effGroup(elem == ELEM_DATE ? "date" : "time");
        float def = elem == ELEM_DATE ? 0f : -5f; // renderer defaults
        return o != null ? (float) o.optDouble("rotation", def) : def;
    }

    private void applyElementRotation(int elem, float rot) {
        if (elem == ELEM_DATE) {
            StudioManager.setDateRotation(requireContext(), rot);
        } else {
            StudioManager.setRotation(requireContext(), rot);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopSecondUpdater();
        renderExecutor.shutdownNow(); // or shutdown() if fragment may be reused — but per-instance is fine
        if (previewBg != null) { previewBg.recycle(); previewBg = null; }
        if (previewMask != null) { previewMask.recycle(); previewMask = null; }
        previewCacheKey = null;
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
        } catch (Exception ignored) {
        }

        if (!needsSeconds) {
            stopSecondUpdater();
            return;
        }

        secondsTickerRunning = true;

        if (secondRunnable == null) {
            secondRunnable = new Runnable() {
                @Override
                public void run() {
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

    private Bitmap composePreview(android.content.Context ctx, String themeJson, int w, int h) {
        try {
            File dir = new File(ctx.getFilesDir(), "wallpaper");

            // Prefer custom background if user uploaded one in Studio
            File bgFile = new File(ctx.getFilesDir(), "custom_bg.png");
            boolean isCustomBg = bgFile.exists();
            if (!isCustomBg) {
                bgFile = new File(dir, "bg.png");
                if (!bgFile.exists()) bgFile = new File(dir, "bg.jpg");
            }

            File maskFile = new File(dir, "mask.png");
            if (!maskFile.exists()) maskFile = new File(dir, "mask.jpg");

            // Cache the decoded+scaled bg/mask, keyed by file + size, so dragging (which
            // re-renders on every touch move) doesn't re-read and re-scale from disk each frame.
            String cacheKey = bgFile.getAbsolutePath() + "|" + (bgFile.exists() ? bgFile.lastModified() : 0)
                    + "|" + isCustomBg + "|" + w + "x" + h + "|" + (maskFile.exists() ? maskFile.lastModified() : 0);
            if (!cacheKey.equals(previewCacheKey)) {
                if (previewBg != null) { previewBg.recycle(); previewBg = null; }
                if (previewMask != null) { previewMask.recycle(); previewMask = null; }
                Bitmap rawBg = bgFile.exists() ? BitmapFactory.decodeFile(bgFile.getAbsolutePath()) : null;
                Bitmap rawMask = maskFile.exists() ? BitmapFactory.decodeFile(maskFile.getAbsolutePath()) : null;
                if (isCustomBg && rawMask != null) { rawMask.recycle(); rawMask = null; }
                previewBg = rawBg != null ? scaleCrop(rawBg, w, h) : null;
                previewMask = rawMask != null ? scaleCrop(rawMask, w, h) : null;
                if (rawBg != null && rawBg != previewBg) rawBg.recycle();
                if (rawMask != null && rawMask != previewMask) rawMask.recycle();
                previewCacheKey = cacheKey;
            }
            Bitmap bg = previewBg;      // cached — do NOT recycle below
            Bitmap mask = previewMask;  // cached — do NOT recycle below

            float maskOpacity = 1.0f;
            try {
                JSONObject root = new JSONObject(themeJson);
                JSONObject time = root.optJSONObject("time");
                if (time != null) {
                    maskOpacity = (float) time.optDouble("maskOpacity", 1.0);
                }
            } catch (Exception ignored) {
            }

            // If custom background, force mask opacity to 0 so preview matches the requirement
            if (isCustomBg) {
                maskOpacity = 0f;
            }

            if (previewRenderer == null) previewRenderer = new ThemeRenderer(ctx);
            ThemeRenderer tr = previewRenderer;
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(result);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

            if (bg != null) c.drawBitmap(bg, 0, 0, p);
            else c.drawColor(android.graphics.Color.BLACK);

            android.graphics.Paint maskPaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            maskPaint.setAlpha((int) (maskOpacity * 255));

            String depthMode = ThemeRenderer.getDepthMode(themeJson);
            if (!"none".equals(depthMode) && mask != null) {
                Bitmap back = tr.renderBackLayer(themeJson, w, h, true, 0, 0);
                Bitmap front = tr.renderFrontLayer(themeJson, w, h, true, 0, 0);
                if (back != null) {
                    c.drawBitmap(back, 0, 0, p);
                    back.recycle();
                }
                c.drawBitmap(mask, 0, 0, maskPaint);
                if (front != null) {
                    c.drawBitmap(front, 0, 0, p);
                    front.recycle();
                }
            } else {
                Bitmap textBmp = tr.renderThemeBitmap(themeJson, w, h, true, 0, 0);
                if (textBmp != null) {
                    c.drawBitmap(textBmp, 0, 0, p);
                    textBmp.recycle();
                }
                if (mask != null && maskOpacity > 0f) {
                    c.drawBitmap(mask, 0, 0, maskPaint);
                }
                try {
                    JSONObject root = new JSONObject(themeJson);
                    JSONObject date = root.optJSONObject("date");
                    if (date != null && date.optBoolean("aboveMask", false)) {
                        Bitmap dateFront = tr.renderDateFrontOnly(themeJson, w, h, true, 0, 0, 0, 0, -1, 1f, ThemeRenderer.ANIM_FADE_SCALE);
                        if (dateFront != null) {
                            c.drawBitmap(dateFront, 0, 0, p);
                            dateFront.recycle();
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // bg/mask are cached and reused across frames — do not recycle them here.
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private android.graphics.Bitmap scaleCrop(android.graphics.Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        float scaleX = (float) w / src.getWidth();
        float scaleY = (float) h / src.getHeight();
        float scale = Math.max(scaleX, scaleY);
        int sw = Math.round(src.getWidth() * scale);
        int sh = Math.round(src.getHeight() * scale);
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true);
        int offX = (sw - w) / 2, offY = (sh - h) / 2;
        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(scaled, offX, offY, w, h);
        if (scaled != src && scaled != cropped) scaled.recycle();
        return cropped;
    }

    public void broadcastChange() {
        // Do nothing here so we don't spam the actual system wallpaper with redraws while editing.
        // The preview is already updated via scheduleRefresh().
    }

    public void notifyWallpaperService() {
        try {
            Intent i = new Intent(SettingsManager.ACTION_SETTINGS_CHANGED);
            i.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // ── Shared nudge helper ──────────────────────────────────────────────────

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

    private void saveCustomBackgroundFromUri(@NonNull Uri uri) throws Exception {
        // Keep the filename consistent with the wallpaper engine
        java.io.File dest = new java.io.File(requireContext().getFilesDir(), "custom_bg.png");

        try (java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
             java.io.OutputStream os = new java.io.FileOutputStream(dest)) {
            if (is == null) throw new IllegalStateException("Can't open selected image");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }

    private void clearCustomBackground() {
        java.io.File f = new java.io.File(requireContext().getFilesDir(), "custom_bg.png");
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CUSTOM_BG) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        try {
            saveCustomBackgroundFromUri(uri);
            scheduleRefresh();
            broadcastChange();
            if (getView() != null) {
                View rm = getView().findViewById(R.id.btn_remove_custom_bg);
                if (rm != null) rm.setVisibility(View.VISIBLE);
            }
            android.widget.Toast.makeText(requireContext(), "Custom background saved", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("StudioFragment", "Failed to save custom background", e);
            android.widget.Toast.makeText(requireContext(), "Failed to load image", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    public interface OnStudioResetListener {
        void onStudioReset();
    }

    interface IntConsumer {
        void accept(int v);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
            if (seekSize != null) {
                seekSize.setProgress(Math.min(1200, Math.max(0, size)));
                if (tvSize != null) tvSize.setText(size + "sp");
            }

            SeekBar seekX = getView().findViewById(R.id.seek_posx);
            TextView tvX = getView().findViewById(R.id.tv_posx_val);
            int x = (int) (t.optDouble("x", 0.5) * 100);
            if (seekX != null) {
                seekX.setProgress(Math.min(100, Math.max(0, x)));
                if (tvX != null) tvX.setText(x + "%");
            }

            SeekBar seekY = getView().findViewById(R.id.seek_posy);
            TextView tvY = getView().findViewById(R.id.tv_posy_val);
            int y = (int) (t.optDouble("y", 0.65) * 100);
            if (seekY != null) {
                seekY.setProgress(Math.min(100, Math.max(0, y)));
                if (tvY != null) tvY.setText(y + "%");
            }

            SeekBar seekRot = getView().findViewById(R.id.seek_rot);
            TextView tvRot = getView().findViewById(R.id.tv_rot_val);
            float rot = (float) t.optDouble("rotation", 0);
            if (seekRot != null) {
                seekRot.setProgress((int) (rot + 180));
                if (tvRot != null) tvRot.setText((int) rot + "°");
            }

            SeekBar seekOp = getView().findViewById(R.id.seek_opacity);
            TextView tvOp = getView().findViewById(R.id.tv_opacity_val);
            int op = (int) (t.optDouble("opacity", 1.0) * 100);
            if (seekOp != null) {
                seekOp.setProgress(Math.min(100, Math.max(0, op)));
                if (tvOp != null) tvOp.setText(op + "%");
            }
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_basics, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;

            JSONObject effectiveTime = st.getEffectiveTime();

            // Custom wallpaper background
            View btnUpload = v.findViewById(R.id.btn_upload_custom_bg);
            View btnRemove = v.findViewById(R.id.btn_remove_custom_bg);
            if (btnUpload != null) {
                btnUpload.setOnClickListener(b -> {
                    com.walle.wallpaper.ui.common.AdManager.showInterstitial(st.requireActivity(), () -> {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        st.startActivityForResult(Intent.createChooser(intent, "Select image"), REQ_PICK_CUSTOM_BG);
                    });
                });
            }
            if (btnRemove != null) {
                java.io.File f = new java.io.File(st.requireContext().getFilesDir(), "custom_bg.png");
                btnRemove.setVisibility(f.exists() ? View.VISIBLE : View.GONE);

                btnRemove.setOnClickListener(b -> {
                    com.walle.wallpaper.ui.common.AdManager.showInterstitial(st.requireActivity(), () -> {
                        st.clearCustomBackground();
                        st.scheduleRefresh();
                        st.broadcastChange();
                        btnRemove.setVisibility(View.GONE);
                        android.widget.Toast.makeText(st.requireContext(), "Custom background removed", android.widget.Toast.LENGTH_SHORT).show();
                    });
                });
            }

            // ── Font Size ──
            SeekBar seekSize = v.findViewById(R.id.seek_size);
            TextView tvSize = v.findViewById(R.id.tv_size_val);
            int initSize = (int) effectiveTime.optDouble("size", 520);
            if (seekSize != null) {
                seekSize.setProgress(Math.min(1200, Math.max(0, initSize)));
                if (tvSize != null) tvSize.setText(initSize + "sp");
                seekSize.setOnSeekBarChangeListener(simple(val -> {
                    if (tvSize != null) tvSize.setText(val + "sp");
                    StudioManager.setFontSize(requireContext(), val);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            View btnResetSize = v.findViewById(R.id.btn_reset_size);
            if (btnResetSize != null) btnResetSize.setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "size");
                JSONObject base = st.getEffectiveTime();
                int s2 = (int) base.optDouble("size", 520);
                if (seekSize != null) seekSize.setProgress(Math.min(1200, Math.max(0, s2)));
                if (tvSize != null) tvSize.setText(s2 + "sp");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnMinusSize = v.findViewById(R.id.btn_minus_size);
            if (btnMinusSize != null) btnMinusSize.setOnClickListener(btn -> {
                if (seekSize == null) return;
                int next = Math.max(0, Math.min(seekSize.getMax(), seekSize.getProgress() - 1));
                seekSize.setProgress(next);
                if (tvSize != null) tvSize.setText(next + "sp");
                StudioManager.setFontSize(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnPlusSize = v.findViewById(R.id.btn_plus_size);
            if (btnPlusSize != null) btnPlusSize.setOnClickListener(btn -> {
                if (seekSize == null) return;
                int next = Math.max(0, Math.min(seekSize.getMax(), seekSize.getProgress() + 1));
                seekSize.setProgress(next);
                if (tvSize != null) tvSize.setText(next + "sp");
                StudioManager.setFontSize(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Position X ──
            SeekBar seekX = v.findViewById(R.id.seek_posx);
            TextView tvX = v.findViewById(R.id.tv_posx_val);
            int initX = (int) (effectiveTime.optDouble("x", 0.5) * 100);
            if (seekX != null) {
                seekX.setProgress(initX);
                if (tvX != null) tvX.setText(initX + "%");
                seekX.setOnSeekBarChangeListener(simple(val -> {
                    if (tvX != null) tvX.setText(val + "%");
                    StudioManager.setPosX(requireContext(), val / 100f);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            View btnResetX = v.findViewById(R.id.btn_reset_posx);
            if (btnResetX != null) btnResetX.setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "x");
                int x2 = (int) (st.getEffectiveTime().optDouble("x", 0.5) * 100);
                if (seekX != null) seekX.setProgress(Math.min(100, Math.max(0, x2)));
                if (tvX != null) tvX.setText(x2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnMinusX = v.findViewById(R.id.btn_minus_posx);
            if (btnMinusX != null) btnMinusX.setOnClickListener(btn -> {
                if (seekX == null) return;
                int next = Math.max(0, Math.min(seekX.getMax(), seekX.getProgress() - 1));
                seekX.setProgress(next);
                if (tvX != null) tvX.setText(next + "%");
                StudioManager.setPosX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnPlusX = v.findViewById(R.id.btn_plus_posx);
            if (btnPlusX != null) btnPlusX.setOnClickListener(btn -> {
                if (seekX == null) return;
                int next = Math.max(0, Math.min(seekX.getMax(), seekX.getProgress() + 1));
                seekX.setProgress(next);
                if (tvX != null) tvX.setText(next + "%");
                StudioManager.setPosX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Position Y ──
            SeekBar seekY = v.findViewById(R.id.seek_posy);
            TextView tvY = v.findViewById(R.id.tv_posy_val);
            int initY = (int) (effectiveTime.optDouble("y", 0.65) * 100);
            if (seekY != null) {
                seekY.setProgress(initY);
                if (tvY != null) tvY.setText(initY + "%");
                seekY.setOnSeekBarChangeListener(simple(val -> {
                    if (tvY != null) tvY.setText(val + "%");
                    StudioManager.setPosY(requireContext(), val / 100f);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            View btnResetY = v.findViewById(R.id.btn_reset_posy);
            if (btnResetY != null) btnResetY.setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "y");
                int y2 = (int) (st.getEffectiveTime().optDouble("y", 0.65) * 100);
                if (seekY != null) seekY.setProgress(Math.min(100, Math.max(0, y2)));
                if (tvY != null) tvY.setText(y2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnMinusY = v.findViewById(R.id.btn_minus_posy);
            if (btnMinusY != null) btnMinusY.setOnClickListener(btn -> {
                if (seekY == null) return;
                int next = Math.max(0, Math.min(seekY.getMax(), seekY.getProgress() - 1));
                seekY.setProgress(next);
                if (tvY != null) tvY.setText(next + "%");
                StudioManager.setPosY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnPlusY = v.findViewById(R.id.btn_plus_posy);
            if (btnPlusY != null) btnPlusY.setOnClickListener(btn -> {
                if (seekY == null) return;
                int next = Math.max(0, Math.min(seekY.getMax(), seekY.getProgress() + 1));
                seekY.setProgress(next);
                if (tvY != null) tvY.setText(next + "%");
                StudioManager.setPosY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Rotation ──
            SeekBar seekRot = v.findViewById(R.id.seek_rot);
            TextView tvRot = v.findViewById(R.id.tv_rot_val);
            float initRot = (float) effectiveTime.optDouble("rotation", 0);
            if (seekRot != null) {
                seekRot.setProgress((int) (initRot + 180));
                if (tvRot != null) tvRot.setText((int) initRot + "°");
                seekRot.setOnSeekBarChangeListener(simple(val -> {
                    float d = val - 180f;
                    if (tvRot != null) tvRot.setText((int) d + "°");
                    StudioManager.setRotation(requireContext(), d);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            View btnResetRot = v.findViewById(R.id.btn_reset_rot);
            if (btnResetRot != null) btnResetRot.setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "rotation");
                float r2 = (float) st.getEffectiveTime().optDouble("rotation", 0);
                if (seekRot != null) seekRot.setProgress((int) (r2 + 180));
                if (tvRot != null) tvRot.setText((int) r2 + "°");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnRot90 = v.findViewById(R.id.btn_rot_90_plus);
            if (btnRot90 != null) btnRot90.setOnClickListener(b -> {
                if (seekRot == null) return;
                int currentProg = seekRot.getProgress();
                int nextProg = currentProg + 90;
                if (nextProg > seekRot.getMax()) {
                    nextProg = nextProg - seekRot.getMax();
                }
                seekRot.setProgress(nextProg);
                float d = nextProg - 180f;
                if (tvRot != null) tvRot.setText((int) d + "°");
                StudioManager.setRotation(requireContext(), d);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnMinusRot = v.findViewById(R.id.btn_minus_rot);
            if (btnMinusRot != null) btnMinusRot.setOnClickListener(b -> {
                if (seekRot == null) return;
                int next = Math.max(0, Math.min(seekRot.getMax(), seekRot.getProgress() - 1));
                seekRot.setProgress(next);
                float d = next - 180f;
                if (tvRot != null) tvRot.setText((int) d + "°");
                StudioManager.setRotation(requireContext(), d);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnPlusRot = v.findViewById(R.id.btn_plus_rot);
            if (btnPlusRot != null) btnPlusRot.setOnClickListener(b -> {
                if (seekRot == null) return;
                int next = Math.max(0, Math.min(seekRot.getMax(), seekRot.getProgress() + 1));
                seekRot.setProgress(next);
                float d = next - 180f;
                if (tvRot != null) tvRot.setText((int) d + "°");
                StudioManager.setRotation(requireContext(), d);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Opacity ──
            SeekBar seekOp = v.findViewById(R.id.seek_opacity);
            TextView tvOp = v.findViewById(R.id.tv_opacity_val);
            int initOp = (int) (effectiveTime.optDouble("opacity", 1.0) * 100);
            if (seekOp != null) {
                seekOp.setProgress(initOp);
                if (tvOp != null) tvOp.setText(initOp + "%");
                seekOp.setOnSeekBarChangeListener(simple(val -> {
                    if (tvOp != null) tvOp.setText(val + "%");
                    StudioManager.setOpacity(requireContext(), val / 100f);
                    st.scheduleRefresh();
                    st.broadcastChange();
                }));
            }
            View btnResetOpacity = v.findViewById(R.id.btn_reset_opacity);
            if (btnResetOpacity != null) btnResetOpacity.setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "opacity");
                int op2 = (int) (st.getEffectiveTime().optDouble("opacity", 1.0) * 100);
                if (seekOp != null) seekOp.setProgress(Math.min(100, Math.max(0, op2)));
                if (tvOp != null) tvOp.setText(op2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnMinusTextOpacity = v.findViewById(R.id.btn_minus_textopacity);
            if (btnMinusTextOpacity != null) btnMinusTextOpacity.setOnClickListener(btn -> {
                if (seekOp == null) return;
                int next = Math.max(0, Math.min(seekOp.getMax(), seekOp.getProgress() - 1));
                seekOp.setProgress(next);
                if (tvOp != null) tvOp.setText(next + "%");
                StudioManager.setOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            View btnPlusTextOpacity = v.findViewById(R.id.btn_plus_textopacity);
            if (btnPlusTextOpacity != null) btnPlusTextOpacity.setOnClickListener(btn -> {
                if (seekOp == null) return;
                int next = Math.max(0, Math.min(seekOp.getMax(), seekOp.getProgress() + 1));
                seekOp.setProgress(next);
                if (tvOp != null) tvOp.setText(next + "%");
                StudioManager.setOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            return v;
        }
    }

    // ── PAGE 2: Typography ───────────────────────────────────────────────────
    public static class TypographyPage extends Fragment implements StudioFragment.OnStudioResetListener {

        /** Imports a font file the user already has on their device (no upload involved). */
        private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> pickFontFile =
                registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() != android.app.Activity.RESULT_OK
                                    || result.getData() == null || result.getData().getData() == null) return;
                            importFontFromUri(result.getData().getData());
                        });

        /** Copies a picked font into custom_fonts/ and registers it for this device only. */
        private void importFontFromUri(android.net.Uri uri) {
            if (!isAdded()) return;
            android.content.Context ctx = requireContext().getApplicationContext();
            String display = queryDisplayName(uri);
            boolean otf = display != null && display.toLowerCase(java.util.Locale.US).endsWith(".otf");
            String id = "userfont_" + System.currentTimeMillis() + (otf ? ".otf" : ".ttf");
            String name = display != null ? display.replaceAll("(?i)\\.(ttf|otf)$", "").trim() : "";
            if (name.isEmpty()) name = "My Font";

            java.io.File dest = com.walle.wallpaper.util.UserFontStore.fontFile(ctx, id);
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                if (in == null) throw new java.io.IOException("Cannot open file");
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                Toast.makeText(ctx, "Couldn't import font: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }

            // Reject anything Android can't actually use as a typeface, so a bad pick can't
            // silently turn the clock into the fallback font later.
            try {
                android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(dest);
                if (tf == null) throw new IllegalStateException("not a font");
            } catch (Throwable bad) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                Toast.makeText(ctx, "That file isn't a usable font (.ttf/.otf)", Toast.LENGTH_LONG).show();
                return;
            }

            com.walle.wallpaper.util.UserFontStore.add(ctx, id, name);
            ThemeRenderer.invalidateFontCache(id);
            rebuildFontList(ctx);
            StudioFragment st = getStudio(this);
            if (st != null) st.notifyFontListReady();
            Toast.makeText(ctx, "Added \"" + name + "\"", Toast.LENGTH_SHORT).show();
        }

        @Nullable
        private String queryDisplayName(android.net.Uri uri) {
            try (android.database.Cursor c = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        /** Downloads one font from the built-in library into this device's font list. */
        private void addFontFromLibrary(com.walle.wallpaper.util.GoogleFontCatalog.Font font) {
            android.content.Context ctx = requireContext().getApplicationContext();
            if (com.walle.wallpaper.util.UserFontStore.hasName(ctx, font.name)) {
                Toast.makeText(ctx, font.name + " is already in your fonts", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(ctx, "Downloading " + font.name + "…", Toast.LENGTH_SHORT).show();
            final String id = "userfont_" + System.currentTimeMillis() + ".ttf";
            final java.io.File dest = com.walle.wallpaper.util.UserFontStore.fontFile(ctx, id);
            new Thread(() -> {
                boolean ok = false;
                try {
                    //noinspection ResultOfMethodCallIgnored
                    dest.getParentFile().mkdirs();
                    new com.walle.wallpaper.util.DownloadWithProgress().download(font.url, dest, null);
                    android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(dest);
                    ok = tf != null;
                } catch (Throwable ignored) {
                }
                final boolean success = ok;
                if (!success && dest.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                }
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (!success) {
                        Toast.makeText(ctx, "Couldn't download " + font.name, Toast.LENGTH_LONG).show();
                        return;
                    }
                    com.walle.wallpaper.util.UserFontStore.add(ctx, id, font.name);
                    ThemeRenderer.invalidateFontCache(id);
                    rebuildFontList(ctx);
                    StudioFragment st = getStudio(this);
                    if (st != null) st.notifyFontListReady();
                    Toast.makeText(ctx, "Added " + font.name, Toast.LENGTH_SHORT).show();
                });
            }).start();
        }

        /** "+ Add" → choose between the built-in library and the user's own file. */
        private void showAddFontDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Add a font")
                    .setItems(new CharSequence[]{"Browse font library", "Pick a file from my phone"}, (d, which) -> {
                        if (which == 0) {
                            showFontLibraryDialog();
                        } else {
                            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            i.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                                    new String[]{"font/ttf", "font/otf", "application/x-font-ttf",
                                            "application/x-font-otf", "application/octet-stream"});
                            try {
                                pickFontFile.launch(i);
                            } catch (Exception e) {
                                Toast.makeText(requireContext(), "No file picker available", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private void showFontLibraryDialog() {
            com.walle.wallpaper.util.GoogleFontCatalog.Font[] fonts =
                    com.walle.wallpaper.util.GoogleFontCatalog.FONTS;
            new AlertDialog.Builder(requireContext())
                    .setTitle("Font library")
                    .setItems(com.walle.wallpaper.util.GoogleFontCatalog.names(), (d, which) -> {
                        if (which >= 0 && which < fonts.length) addFontFromLibrary(fonts[which]);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        /** Long-press on a user-added font offers to remove it. */
        private boolean confirmRemoveUserFont(FontPickerAdapter.FontItem item, Runnable after) {
            android.content.Context ctx = requireContext().getApplicationContext();
            if (!com.walle.wallpaper.util.UserFontStore.isUserFont(ctx, item.id)) return false;
            new AlertDialog.Builder(requireContext())
                    .setTitle("Remove font")
                    .setMessage("Remove \"" + item.displayName + "\" from your fonts?")
                    .setPositiveButton("Remove", (d, w) -> {
                        com.walle.wallpaper.util.UserFontStore.remove(ctx, item.id);
                        rebuildFontList(ctx);
                        StudioFragment st = getStudio(this);
                        if (st != null) {
                            st.notifyFontListReady();
                            st.scheduleRefresh();
                            st.broadcastChange();
                        }
                        if (after != null) after.run();
                        Toast.makeText(ctx, "Font removed", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }

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

            RadioGroup rgStyle = getView().findViewById(R.id.rg_clock_style);
            String curStyle = t.optString("clockStyle", "HH:MM");
            setClockStyleRadio(rgStyle, curStyle);

            RadioGroup rgDepth = getView().findViewById(R.id.rg_depth);
            String dm = t.optString("depthMode", "none");
            if ("hoursFront".equals(dm)) rgDepth.check(R.id.rb_depth_hoursfront);
            else if ("minuteFront".equals(dm)) rgDepth.check(R.id.rb_depth_minsfront);
            else rgDepth.check(R.id.rb_depth_standard);

            SeekBar seekLs = getView().findViewById(R.id.seek_ls);
            TextView tvLs = getView().findViewById(R.id.tv_ls_val);
            int ls = (int) t.optDouble("letterSpacing", 0);
            if (seekLs != null) {
                seekLs.setProgress(Math.min(200, Math.max(0, ls + 100)));
                if (tvLs != null) tvLs.setText(String.valueOf(ls));
            }

            SeekBar seekVGap = getView().findViewById(R.id.seek_vgap);
            TextView tvVGap = getView().findViewById(R.id.tv_vgap_val);
            View cardVGap = getView().findViewById(R.id.card_vertical_gap);
            float vg = (float) t.optDouble("verticalGap", 1.0);
            if (seekVGap != null) {
                seekVGap.setProgress(vGapToProgress(vg));
                if (tvVGap != null) tvVGap.setText(formatVGap(vg));
            }
            if (cardVGap != null) {
                boolean isVert = "VERTICAL".equals(curStyle) || "VERTICAL_SS".equals(curStyle);
                cardVGap.setVisibility(isVert ? View.VISIBLE : View.GONE);
            }

            SwitchCompat swConnector = getView().findViewById(R.id.sw_connector_behind);
            View connectorContainer = getView().findViewById(R.id.connector_toggle_container);
            if (swConnector != null)
                swConnector.setChecked(t.optBoolean("connectorBehindMask", true));
            boolean hasSeconds = curStyle.toUpperCase().contains("SS");
            if (connectorContainer != null)
                connectorContainer.setVisibility(hasSeconds ? View.VISIBLE : View.GONE);

            RecyclerView rvFonts = getView().findViewById(R.id.rv_fonts);
            if (rvFonts != null && rvFonts.getAdapter() instanceof FontPickerAdapter)
                ((FontPickerAdapter) rvFonts.getAdapter()).setSelected(t.optString("font", "main3.ttf"));

            InlineColorPicker cpHour = getView().findViewById(R.id.color_picker_hour);
            View swHour = getView().findViewById(R.id.swatch_hour);
            SwitchCompat swHourOutline = getView().findViewById(R.id.sw_hour_outline);
            String h = t.optString("hourColor", "#FFFFFF");
            if (cpHour != null) cpHour.setSelectedColor(h);
            if (swHour != null) trySetBg(swHour, h);
            if (swHourOutline != null)
                swHourOutline.setChecked(t.optBoolean("hourOutlineOnly", false));

            InlineColorPicker cpMin = getView().findViewById(R.id.color_picker_minute);
            View swMin = getView().findViewById(R.id.swatch_minute);
            SwitchCompat swMinOutline = getView().findViewById(R.id.sw_min_outline);
            String m = t.optString("minuteColor", "#FF5FA2");
            if (cpMin != null) cpMin.setSelectedColor(m);
            if (swMin != null) trySetBg(swMin, m);
            if (swMinOutline != null)
                swMinOutline.setChecked(t.optBoolean("minuteOutlineOnly", false));

            SwitchCompat swGrad = getView().findViewById(R.id.sw_time_gradient);
            View gradAngleLayout = getView().findViewById(R.id.layout_time_gradient_angle);
            SeekBar seekGradAng = getView().findViewById(R.id.seek_time_gradient_angle);
            TextView tvGradAng = getView().findViewById(R.id.tv_time_gradient_angle_val);
            if (swGrad != null) {
                boolean ge = t.optBoolean("timeGradientEnabled", false);
                swGrad.setChecked(ge);
                if (gradAngleLayout != null)
                    gradAngleLayout.setVisibility(ge ? View.VISIBLE : View.GONE);
            }
            if (seekGradAng != null) {
                int ang = (int) (t.optDouble("timeGradientAngle", 0) % 360);
                if (ang < 0) ang += 360;
                seekGradAng.setProgress(ang);
                if (tvGradAng != null) tvGradAng.setText(ang + "°");
            }

            st.startSecondUpdaterIfNeeded();
        }

        // Vertical-gap seekbar ↔ multiplier mapping. progress 0..280 → gap 0.2..3.0,
        // with the default multiplier 1.0 landing at progress 80.
        private static int vGapToProgress(float gap) {
            return Math.max(0, Math.min(280, Math.round((gap - 0.2f) * 100f)));
        }

        private static float progressToVGap(int progress) {
            return 0.2f + progress / 100f;
        }

        private static String formatVGap(float gap) {
            return String.format(java.util.Locale.US, "%.1f×", gap);
        }

        private void setClockStyleRadio(RadioGroup rg, String style) {
            if (rg == null) return;
            if ("HHMM".equals(style)) rg.check(R.id.rb_style_hhmm);
            else if ("HH MM".equals(style)) rg.check(R.id.rb_style_hh_space_mm);
            else if ("HH.MM".equals(style)) rg.check(R.id.rb_style_hh_mm_dot);
            else if ("HH:MM:SS".equals(style)) rg.check(R.id.rb_style_hhmmss);
            else if ("HH/MM".equals(style)) rg.check(R.id.rb_style_hh_mm_slash);
            else if ("HH/MM/SS".equals(style)) rg.check(R.id.rb_style_hh_mm_ss_slash);
            else if ("VERTICAL".equals(style)) rg.check(R.id.rb_style_vertical);
            else if ("VERTICAL_SS".equals(style)) rg.check(R.id.rb_style_vertical_ss);
            else rg.check(R.id.rb_style_hh_mm);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_typography, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;

            JSONObject effectiveTime = st.getEffectiveTime();

            // Clock Style
            RadioGroup rgStyle = v.findViewById(R.id.rg_clock_style);
            setClockStyleRadio(rgStyle, effectiveTime.optString("clockStyle", "HH:MM"));

            // Depth
            RadioGroup rgDepth = v.findViewById(R.id.rg_depth);
            String curDepth = effectiveTime.optString("depthMode", "none");
            if ("hoursFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_hoursfront);
            else if ("minuteFront".equals(curDepth)) rgDepth.check(R.id.rb_depth_minsfront);
            else rgDepth.check(R.id.rb_depth_standard);
            rgDepth.setOnCheckedChangeListener((g, id) -> {
                String dm = id == R.id.rb_depth_hoursfront ? "hoursFront" : id == R.id.rb_depth_minsfront ? "minuteFront" : "none";
                StudioManager.setDepthMode(requireContext(), dm);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_depth).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "depthMode");
                String dm = st.getEffectiveTime().optString("depthMode", "none");
                if ("hoursFront".equals(dm)) rgDepth.check(R.id.rb_depth_hoursfront);
                else if ("minuteFront".equals(dm)) rgDepth.check(R.id.rb_depth_minsfront);
                else rgDepth.check(R.id.rb_depth_standard);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Connector toggle
            View connectorContainer = v.findViewById(R.id.connector_toggle_container);
            SwitchCompat swConnector = v.findViewById(R.id.sw_connector_behind);
            swConnector.setChecked(effectiveTime.optBoolean("connectorBehindMask", true));
            swConnector.setOnCheckedChangeListener((b2, ch) -> {
                StudioManager.setConnectorBehindMask(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            View cardVerticalGap = v.findViewById(R.id.card_vertical_gap);
            Runnable updateConnectorVisibility = () -> {
                int checkedId = rgStyle.getCheckedRadioButtonId();
                boolean hasSeconds = (checkedId == R.id.rb_style_hhmmss || checkedId == R.id.rb_style_hh_mm_ss_slash || checkedId == R.id.rb_style_vertical_ss);
                connectorContainer.setVisibility(hasSeconds ? View.VISIBLE : View.GONE);
                // The vertical-spacing control only applies to the stacked vertical styles.
                boolean isVert = (checkedId == R.id.rb_style_vertical || checkedId == R.id.rb_style_vertical_ss);
                if (cardVerticalGap != null) cardVerticalGap.setVisibility(isVert ? View.VISIBLE : View.GONE);
            };
            updateConnectorVisibility.run();

            rgStyle.setOnCheckedChangeListener((g, id) -> {
                String st2 = id == R.id.rb_style_hhmm ? "HHMM" : id == R.id.rb_style_hh_space_mm ? "HH MM" : id == R.id.rb_style_hh_mm_dot ? "HH.MM" : id == R.id.rb_style_hhmmss ? "HH:MM:SS" : id == R.id.rb_style_hh_mm_slash ? "HH/MM" : id == R.id.rb_style_hh_mm_ss_slash ? "HH/MM/SS" : id == R.id.rb_style_vertical ? "VERTICAL" : id == R.id.rb_style_vertical_ss ? "VERTICAL_SS" : "HH:MM";
                StudioManager.setClockStyle(requireContext(), st2);
                st.scheduleRefresh();
                st.broadcastChange();
                updateConnectorVisibility.run();
                st.startSecondUpdaterIfNeeded();
            });
            v.findViewById(R.id.btn_reset_clock_style).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "clockStyle");
                setClockStyleRadio(rgStyle, st.getEffectiveTime().optString("clockStyle", "HH:MM"));
                st.scheduleRefresh();
                st.broadcastChange();
                updateConnectorVisibility.run();
                st.startSecondUpdaterIfNeeded();
            });

            // Font
            RecyclerView rvFonts = v.findViewById(R.id.rv_fonts);
            rvFonts.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            FontPickerAdapter fa = new FontPickerAdapter(requireContext(), StudioFragment.loadedFontsList, effectiveTime.optString("font", "main3.ttf"));
            fa.setListener(fontItem -> {
                StudioManager.setFont(requireContext(), fontItem.id);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            fa.setLongPressListener(fontItem -> confirmRemoveUserFont(fontItem, fa::notifyDataSetChanged));
            rvFonts.setAdapter(fa);

            View btnAddFont = v.findViewById(R.id.btn_add_font);
            if (btnAddFont != null) btnAddFont.setOnClickListener(b -> showAddFontDialog());
            v.findViewById(R.id.btn_reset_font).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "font");
                fa.setSelected("main3.ttf");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Letter Spacing ──
            SeekBar seekLs = v.findViewById(R.id.seek_ls);
            TextView tvLs = v.findViewById(R.id.tv_ls_val);
            int initLs = (int) effectiveTime.optDouble("letterSpacing", 0);
            seekLs.setProgress(Math.min(200, Math.max(0, initLs + 100)));
            tvLs.setText(String.valueOf(initLs));
            seekLs.setOnSeekBarChangeListener(simple(val -> {
                int rv = val - 100;
                tvLs.setText(String.valueOf(rv));
                StudioManager.setLetterSpacing(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_ls).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "letterSpacing");
                int ls2 = (int) st.getEffectiveTime().optDouble("letterSpacing", 0);
                seekLs.setProgress(Math.min(200, Math.max(0, ls2 + 100)));
                tvLs.setText(String.valueOf(ls2));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_ls).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekLs.getMax(), seekLs.getProgress() - 1));
                seekLs.setProgress(next);
                int rv = next - 100;
                tvLs.setText(String.valueOf(rv));
                StudioManager.setLetterSpacing(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_ls).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekLs.getMax(), seekLs.getProgress() + 1));
                seekLs.setProgress(next);
                int rv = next - 100;
                tvLs.setText(String.valueOf(rv));
                StudioManager.setLetterSpacing(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Vertical Row Spacing (Vertical / Vert SS styles) ──
            SeekBar seekVGap = v.findViewById(R.id.seek_vgap);
            TextView tvVGap = v.findViewById(R.id.tv_vgap_val);
            float initVGap = (float) effectiveTime.optDouble("verticalGap", 1.0);
            seekVGap.setProgress(vGapToProgress(initVGap));
            tvVGap.setText(formatVGap(initVGap));
            seekVGap.setOnSeekBarChangeListener(simple(val -> {
                float gval = progressToVGap(val);
                tvVGap.setText(formatVGap(gval));
                StudioManager.setVerticalGap(requireContext(), gval);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_vgap).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "verticalGap");
                float gval = (float) st.getEffectiveTime().optDouble("verticalGap", 1.0);
                seekVGap.setProgress(vGapToProgress(gval));
                tvVGap.setText(formatVGap(gval));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_vgap).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekVGap.getMax(), seekVGap.getProgress() - 5));
                seekVGap.setProgress(next);
                float gval = progressToVGap(next);
                tvVGap.setText(formatVGap(gval));
                StudioManager.setVerticalGap(requireContext(), gval);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_vgap).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekVGap.getMax(), seekVGap.getProgress() + 5));
                seekVGap.setProgress(next);
                float gval = progressToVGap(next);
                tvVGap.setText(formatVGap(gval));
                StudioManager.setVerticalGap(requireContext(), gval);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Hour Color
            String[] hc = {effectiveTime.optString("hourColor", "#FFFFFF")};
            View swHour = v.findViewById(R.id.swatch_hour);
            trySetBg(swHour, hc[0]);
            InlineColorPicker cpHour = v.findViewById(R.id.color_picker_hour);
            cpHour.setSelectedColor(hc[0]);
            cpHour.setOnColorSelectedListener(hex -> {
                hc[0] = hex;
                trySetBg(swHour, hex);
                StudioManager.setHourColor(requireContext(), hex);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            SwitchCompat swHourOutline = v.findViewById(R.id.sw_hour_outline);
            swHourOutline.setChecked(effectiveTime.optBoolean("hourOutlineOnly", false));
            swHourOutline.setOnCheckedChangeListener((b2, ch) -> {
                StudioManager.putTime(requireContext(), "hourOutlineOnly", ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_hour_color).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "hourColor");
                StudioManager.resetTimeKey(requireContext(), "hourOutlineOnly");
                swHourOutline.setChecked(false);
                hc[0] = st.getEffectiveTime().optString("hourColor", "#FFFFFF");
                trySetBg(swHour, hc[0]);
                cpHour.setSelectedColor(hc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Minute Color
            String[] mc = {effectiveTime.optString("minuteColor", "#FF5FA2")};
            View swMin = v.findViewById(R.id.swatch_minute);
            trySetBg(swMin, mc[0]);
            InlineColorPicker cpMin = v.findViewById(R.id.color_picker_minute);
            cpMin.setSelectedColor(mc[0]);
            cpMin.setOnColorSelectedListener(hex -> {
                mc[0] = hex;
                trySetBg(swMin, hex);
                StudioManager.setMinuteColor(requireContext(), hex);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            SwitchCompat swMinOutline = v.findViewById(R.id.sw_min_outline);
            swMinOutline.setChecked(effectiveTime.optBoolean("minuteOutlineOnly", false));
            swMinOutline.setOnCheckedChangeListener((b2, ch) -> {
                StudioManager.putTime(requireContext(), "minuteOutlineOnly", ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_min_color).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "minuteColor");
                StudioManager.resetTimeKey(requireContext(), "minuteOutlineOnly");
                swMinOutline.setChecked(false);
                mc[0] = st.getEffectiveTime().optString("minuteColor", "#FF5FA2");
                trySetBg(swMin, mc[0]);
                cpMin.setSelectedColor(mc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Time Gradient ──
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
                tvGradAng.setText(val + "°");
                StudioManager.setTimeGradientAngle(requireContext(), val);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_minus_time_gradient_angle).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekGradAng.getMax(), seekGradAng.getProgress() - 1));
                seekGradAng.setProgress(next);
                tvGradAng.setText(next + "°");
                StudioManager.setTimeGradientAngle(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_time_gradient_angle).setOnClickListener(btn -> {
                int next = Math.max(0, Math.min(seekGradAng.getMax(), seekGradAng.getProgress() + 1));
                seekGradAng.setProgress(next);
                tvGradAng.setText(next + "°");
                StudioManager.setTimeGradientAngle(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            return v;
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

            SwitchCompat swShadow = getView().findViewById(R.id.sw_shadow);
            View shadowCtrl = getView().findViewById(R.id.layout_shadow_controls);
            boolean se = t.optBoolean("shadowEnabled", false);
            if (swShadow != null) swShadow.setChecked(se);
            if (shadowCtrl != null) shadowCtrl.setVisibility(se ? View.VISIBLE : View.GONE);

            SeekBar seekShOp = getView().findViewById(R.id.seek_shadow_opacity);
            TextView tvShOp = getView().findViewById(R.id.tv_shadow_opacity_val);
            int op = (int) (t.optDouble("shadowOpacity", 1.0) * 100);
            if (seekShOp != null) {
                seekShOp.setProgress(Math.min(100, Math.max(0, op)));
                if (tvShOp != null) tvShOp.setText(op + "%");
            }

            SeekBar seekSx = getView().findViewById(R.id.seek_shadow_x);
            SeekBar seekSy = getView().findViewById(R.id.seek_shadow_y);
            TextView tvSx = getView().findViewById(R.id.tv_shadow_x_val);
            TextView tvSy = getView().findViewById(R.id.tv_shadow_y_val);
            int sx = (int) t.optDouble("shadowX", 4);
            int sy = (int) t.optDouble("shadowY", 4);
            if (seekSx != null) {
                seekSx.setProgress(sx + 100);
                if (tvSx != null) tvSx.setText(String.valueOf(sx));
            }
            if (seekSy != null) {
                seekSy.setProgress(sy + 100);
                if (tvSy != null) tvSy.setText(String.valueOf(sy));
            }

            SwitchCompat swStroke = getView().findViewById(R.id.sw_stroke);
            View strokeCtrl = getView().findViewById(R.id.layout_stroke_controls);
            boolean ste = t.optBoolean("strokeEnabled", false);
            if (swStroke != null) swStroke.setChecked(ste);
            if (strokeCtrl != null) strokeCtrl.setVisibility(ste ? View.VISIBLE : View.GONE);

            SeekBar seekStrokeW = getView().findViewById(R.id.seek_stroke_w);
            TextView tvStrokeW = getView().findViewById(R.id.tv_stroke_w_val);
            int sw2 = (int) t.optDouble("strokeWidth", 0);
            if (seekStrokeW != null) {
                seekStrokeW.setProgress(Math.min(50, Math.max(0, sw2)));
                if (tvStrokeW != null) tvStrokeW.setText(String.valueOf(sw2));
            }

            InlineColorPicker cpStroke = getView().findViewById(R.id.color_picker_stroke);
            View swStrokeCol = getView().findViewById(R.id.swatch_stroke);
            String scol = t.optString("strokeColor", "#000000");
            if (cpStroke != null) cpStroke.setSelectedColor(scol);
            if (swStrokeCol != null) trySetBg(swStrokeCol, scol);

            RadioGroup rgTarget = getView().findViewById(R.id.rg_stroke_target);
            String target = t.optString("strokeTarget", "both");
            if (rgTarget != null)
                rgTarget.check("hh".equals(target) ? R.id.rb_stroke_hh : "mm".equals(target) ? R.id.rb_stroke_mm : R.id.rb_stroke_both);

            SwitchCompat swFill = getView().findViewById(R.id.sw_fill_enabled);
            if (swFill != null) swFill.setChecked(t.optBoolean("fillEnabled", true));

            SeekBar seekMask = getView().findViewById(R.id.seek_mask_opacity);
            TextView tvMask = getView().findViewById(R.id.tv_mask_opacity_val);
            int mo = (int) (t.optDouble("maskOpacity", 1.0) * 100);
            if (seekMask != null) {
                seekMask.setProgress(Math.min(100, Math.max(0, mo)));
                if (tvMask != null) tvMask.setText(mo + "%");
            }

            SeekBar seekGlow = getView().findViewById(R.id.seek_glow_radius);
            TextView tvGlow = getView().findViewById(R.id.tv_glow_radius_val);
            int gr = (int) t.optDouble("glowRadius", 0);
            if (seekGlow != null) {
                seekGlow.setProgress(Math.min(200, Math.max(0, gr)));
                if (tvGlow != null) tvGlow.setText(String.valueOf(gr));
            }

            InlineColorPicker cpGlow = getView().findViewById(R.id.color_picker_glow);
            View swGlow = getView().findViewById(R.id.swatch_glow);
            String gcol = t.optString("glowColor", t.optString("minuteColor", "#FFFFFF"));
            if (cpGlow != null) cpGlow.setSelectedColor(gcol);
            if (swGlow != null) trySetBg(swGlow, gcol);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_effects, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;
            JSONObject effectiveTime = st.getEffectiveTime();

            // ── Shadow Toggle ──
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
            v.findViewById(R.id.btn_reset_shadow).setOnClickListener(b -> {
                swShadow.setChecked(false);
                StudioManager.resetTimeKey(requireContext(), "shadowEnabled");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Shadow Opacity ──
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
            v.findViewById(R.id.btn_minus_shadow_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekShOp.getMax(), seekShOp.getProgress() - 1));
                seekShOp.setProgress(next);
                tvShOp.setText(next + "%");
                StudioManager.setShadowOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_shadow_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekShOp.getMax(), seekShOp.getProgress() + 1));
                seekShOp.setProgress(next);
                tvShOp.setText(next + "%");
                StudioManager.setShadowOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Shadow X ──
            SeekBar seekSx = v.findViewById(R.id.seek_shadow_x);
            TextView tvSx = v.findViewById(R.id.tv_shadow_x_val);
            int initShadowX = (int) effectiveTime.optDouble("shadowX", 4);
            seekSx.setProgress(initShadowX + 100);
            tvSx.setText(String.valueOf(initShadowX));
            seekSx.setOnSeekBarChangeListener(simple(val -> {
                int rv = val - 100;
                tvSx.setText(String.valueOf(rv));
                StudioManager.setShadowX(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_shadow_x).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "shadowX");
                int v2 = (int) st.getEffectiveTime().optDouble("shadowX", 4);
                seekSx.setProgress(v2 + 100);
                tvSx.setText(String.valueOf(v2));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_shadow_x).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSx.getMax(), seekSx.getProgress() - 1));
                seekSx.setProgress(next);
                int rv = next - 100;
                tvSx.setText(String.valueOf(rv));
                StudioManager.setShadowX(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_shadow_x).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSx.getMax(), seekSx.getProgress() + 1));
                seekSx.setProgress(next);
                int rv = next - 100;
                tvSx.setText(String.valueOf(rv));
                StudioManager.setShadowX(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Shadow Y ──
            SeekBar seekSy = v.findViewById(R.id.seek_shadow_y);
            TextView tvSy = v.findViewById(R.id.tv_shadow_y_val);
            int initShadowY = (int) effectiveTime.optDouble("shadowY", 4);
            seekSy.setProgress(initShadowY + 100);
            tvSy.setText(String.valueOf(initShadowY));
            seekSy.setOnSeekBarChangeListener(simple(val -> {
                int rv = val - 100;
                tvSy.setText(String.valueOf(rv));
                StudioManager.setShadowY(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_shadow_y).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "shadowY");
                int v2 = (int) st.getEffectiveTime().optDouble("shadowY", 4);
                seekSy.setProgress(v2 + 100);
                tvSy.setText(String.valueOf(v2));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_shadow_y).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSy.getMax(), seekSy.getProgress() - 1));
                seekSy.setProgress(next);
                int rv = next - 100;
                tvSy.setText(String.valueOf(rv));
                StudioManager.setShadowY(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_shadow_y).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSy.getMax(), seekSy.getProgress() + 1));
                seekSy.setProgress(next);
                int rv = next - 100;
                tvSy.setText(String.valueOf(rv));
                StudioManager.setShadowY(requireContext(), rv);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Stroke Toggle ──
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
            v.findViewById(R.id.btn_reset_stroke_toggle).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "strokeEnabled");
                StudioManager.resetTimeKey(requireContext(), "strokeWidth");
                StudioManager.resetTimeKey(requireContext(), "strokeColor");
                StudioManager.resetTimeKey(requireContext(), "strokeTarget");
                StudioManager.resetTimeKey(requireContext(), "fillEnabled");
                onStudioReset();
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Stroke Width ──
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
            v.findViewById(R.id.btn_reset_stroke_w).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "strokeWidth");
                int v2 = (int) st.getEffectiveTime().optDouble("strokeWidth", 0);
                seekStrokeW.setProgress(Math.min(50, Math.max(0, v2)));
                tvStrokeW.setText(String.valueOf(v2));
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_stroke_w).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekStrokeW.getMax(), seekStrokeW.getProgress() - 1));
                seekStrokeW.setProgress(next);
                tvStrokeW.setText(String.valueOf(next));
                StudioManager.setStrokeWidth(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_stroke_w).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekStrokeW.getMax(), seekStrokeW.getProgress() + 1));
                seekStrokeW.setProgress(next);
                tvStrokeW.setText(String.valueOf(next));
                StudioManager.setStrokeWidth(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Stroke target
            RadioGroup rgTarget = v.findViewById(R.id.rg_stroke_target);
            String initTarget = effectiveTime.optString("strokeTarget", "both");
            rgTarget.check("hh".equals(initTarget) ? R.id.rb_stroke_hh : "mm".equals(initTarget) ? R.id.rb_stroke_mm : R.id.rb_stroke_both);
            rgTarget.setOnCheckedChangeListener((g, checkedId) -> {
                String tgt = checkedId == R.id.rb_stroke_hh ? "hh" : checkedId == R.id.rb_stroke_mm ? "mm" : "both";
                StudioManager.setStrokeTarget(requireContext(), tgt);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Stroke Color
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

            // Fill enabled
            SwitchCompat swFill = v.findViewById(R.id.sw_fill_enabled);
            if (swFill != null) {
                swFill.setChecked(effectiveTime.optBoolean("fillEnabled", true));
                swFill.setOnCheckedChangeListener((b2, ch) -> {
                    StudioManager.setFillEnabled(requireContext(), ch);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            // ── Mask Opacity ──
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
            v.findViewById(R.id.btn_minus_mask_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekMask.getMax(), seekMask.getProgress() - 1));
                seekMask.setProgress(next);
                tvMask.setText(next + "%");
                StudioManager.setMaskOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_mask_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekMask.getMax(), seekMask.getProgress() + 1));
                seekMask.setProgress(next);
                tvMask.setText(next + "%");
                StudioManager.setMaskOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Glow Radius ──
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
            v.findViewById(R.id.btn_reset_glow).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "glowRadius");
                StudioManager.resetTimeKey(requireContext(), "glowColor");
                onStudioReset();
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_glow_radius).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekGlow.getMax(), seekGlow.getProgress() - 1));
                seekGlow.setProgress(next);
                tvGlow.setText(String.valueOf(next));
                StudioManager.setGlowRadius(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_glow_radius).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekGlow.getMax(), seekGlow.getProgress() + 1));
                seekGlow.setProgress(next);
                tvGlow.setText(String.valueOf(next));
                StudioManager.setGlowRadius(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // Glow Color
            InlineColorPicker cpGlow = v.findViewById(R.id.color_picker_glow);
            View swGlowSwatch = v.findViewById(R.id.swatch_glow);
            String initGlowCol = effectiveTime.optString("glowColor", effectiveTime.optString("minuteColor", "#FFFFFF"));
            trySetBg(swGlowSwatch, initGlowCol);
            if (cpGlow != null) {
                cpGlow.setSelectedColor(initGlowCol);
                cpGlow.setOnColorSelectedListener(col -> {
                    trySetBg(swGlowSwatch, col);
                    StudioManager.setGlowColor(requireContext(), col);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

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

            setSeekAndText(getView(), R.id.seek_sx, R.id.tv_sx_val, (int) (t.optDouble("stretchX", 1.0) * 100), (int) (t.optDouble("stretchX", 1.0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_sy, R.id.tv_sy_val, (int) (t.optDouble("stretchY", 1.0) * 100), (int) (t.optDouble("stretchY", 1.0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_skewh, R.id.tv_skewh_val, (int) (t.optDouble("skewH", 0) * 100 + 200), (int) (t.optDouble("skewH", 0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_skewv, R.id.tv_skewv_val, (int) (t.optDouble("skewV", 0) * 100 + 200), (int) (t.optDouble("skewV", 0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_skewbh, R.id.tv_skewbh_val, (int) (t.optDouble("skewBottomH", 0) * 100 + 200), (int) (t.optDouble("skewBottomH", 0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_skewlv, R.id.tv_skewlv_val, (int) (t.optDouble("skewLeftV", 0) * 100 + 200), (int) (t.optDouble("skewLeftV", 0) * 100) + "%");
            setSeekAndText(getView(), R.id.seek_skewlo, R.id.tv_skewlo_val, (int) (t.optDouble("skewLeftOnly", 0) * 100 + 200), (int) (t.optDouble("skewLeftOnly", 0) * 100) + "%");
            int curve = Math.max(-100, Math.min(100, (int) Math.round(t.optDouble("curvature", 0) * 100)));
            setSeekAndText(getView(), R.id.seek_curve, R.id.tv_curve_val, curve + 100, curve + "%");
        }

        private void setSeekAndText(View root, int seekId, int tvId, int prog, String text) {
            SeekBar sb = root.findViewById(seekId);
            TextView tv = root.findViewById(tvId);
            if (sb != null) sb.setProgress(Math.max(0, Math.min(sb.getMax(), prog)));
            if (tv != null) tv.setText(text);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_transform, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;
            JSONObject effectiveTime = st.getEffectiveTime();

            // ── Curvature ──
            SeekBar seekCurve = v.findViewById(R.id.seek_curve);
            TextView tvCurve = v.findViewById(R.id.tv_curve_val);
            int initCurvePct = Math.max(-100, Math.min(100, (int) Math.round(effectiveTime.optDouble("curvature", 0) * 100)));
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
            v.findViewById(R.id.btn_minus_curve).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekCurve.getMax(), seekCurve.getProgress() - 1));
                seekCurve.setProgress(next);
                int pct = next - 100;
                tvCurve.setText(pct + "%");
                StudioManager.setCurvature(requireContext(), pct / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_curve).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekCurve.getMax(), seekCurve.getProgress() + 1));
                seekCurve.setProgress(next);
                int pct = next - 100;
                tvCurve.setText(pct + "%");
                StudioManager.setCurvature(requireContext(), pct / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Stretch X ──
            SeekBar seekSx = v.findViewById(R.id.seek_sx);
            TextView tvSx = v.findViewById(R.id.tv_sx_val);
            int initSxPct = (int) (effectiveTime.optDouble("stretchX", 1.0) * 100);
            seekSx.setProgress(Math.min(600, Math.max(0, initSxPct)));
            tvSx.setText(Math.min(600, Math.max(0, initSxPct)) + "%");
            seekSx.setOnSeekBarChangeListener(simple(val -> {
                tvSx.setText(val + "%");
                StudioManager.setStretchX(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_sx).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "stretchX");
                int pct = (int) (st.getEffectiveTime().optDouble("stretchX", 1.0) * 100);
                seekSx.setProgress(Math.min(600, Math.max(0, pct)));
                tvSx.setText(Math.min(600, Math.max(0, pct)) + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_sx).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSx.getMax(), seekSx.getProgress() - 1));
                seekSx.setProgress(next);
                tvSx.setText(next + "%");
                StudioManager.setStretchX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_sx).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSx.getMax(), seekSx.getProgress() + 1));
                seekSx.setProgress(next);
                tvSx.setText(next + "%");
                StudioManager.setStretchX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Stretch Y ──
            SeekBar seekSy = v.findViewById(R.id.seek_sy);
            TextView tvSy = v.findViewById(R.id.tv_sy_val);
            int initSyPct = (int) (effectiveTime.optDouble("stretchY", 1.0) * 100);
            seekSy.setProgress(Math.min(600, Math.max(0, initSyPct)));
            tvSy.setText(Math.min(600, Math.max(0, initSyPct)) + "%");
            seekSy.setOnSeekBarChangeListener(simple(val -> {
                tvSy.setText(val + "%");
                StudioManager.setStretchY(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_sy).setOnClickListener(b -> {
                StudioManager.resetTimeKey(requireContext(), "stretchY");
                int pct = (int) (st.getEffectiveTime().optDouble("stretchY", 1.0) * 100);
                seekSy.setProgress(Math.min(600, Math.max(0, pct)));
                tvSy.setText(Math.min(600, Math.max(0, pct)) + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_sy).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSy.getMax(), seekSy.getProgress() - 1));
                seekSy.setProgress(next);
                tvSy.setText(next + "%");
                StudioManager.setStretchY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_sy).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSy.getMax(), seekSy.getProgress() + 1));
                seekSy.setProgress(next);
                tvSy.setText(next + "%");
                StudioManager.setStretchY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Skew H ──
            SeekBar seekSH = v.findViewById(R.id.seek_skewh);
            TextView tvSH = v.findViewById(R.id.tv_skewh_val);
            float initSH = (float) effectiveTime.optDouble("skewH", 0);
            seekSH.setProgress((int) (initSH * 100 + 200));
            tvSH.setText((int) (initSH * 100) + "%");
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
            v.findViewById(R.id.btn_minus_skewh).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSH.getMax(), seekSH.getProgress() - 1));
                seekSH.setProgress(next);
                tvSH.setText((next - 200) + "%");
                StudioManager.setSkewH(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_skewh).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSH.getMax(), seekSH.getProgress() + 1));
                seekSH.setProgress(next);
                tvSH.setText((next - 200) + "%");
                StudioManager.setSkewH(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Skew V ──
            SeekBar seekSV = v.findViewById(R.id.seek_skewv);
            TextView tvSV = v.findViewById(R.id.tv_skewv_val);
            float initSV = (float) effectiveTime.optDouble("skewV", 0);
            seekSV.setProgress((int) (initSV * 100 + 200));
            tvSV.setText((int) initSV + "%");
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
            v.findViewById(R.id.btn_minus_skewv).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSV.getMax(), seekSV.getProgress() - 1));
                seekSV.setProgress(next);
                tvSV.setText((next - 200) + "%");
                StudioManager.setSkewV(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_skewv).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekSV.getMax(), seekSV.getProgress() + 1));
                seekSV.setProgress(next);
                tvSV.setText((next - 200) + "%");
                StudioManager.setSkewV(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Bottom Skew ──
            SeekBar seekBH = v.findViewById(R.id.seek_skewbh);
            TextView tvBH = v.findViewById(R.id.tv_skewbh_val);
            float initBH = (float) effectiveTime.optDouble("skewBottomH", 0);
            seekBH.setProgress((int) (initBH * 100 + 200));
            tvBH.setText((int) initBH + "%");
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
            v.findViewById(R.id.btn_minus_skewbh).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekBH.getMax(), seekBH.getProgress() - 1));
                seekBH.setProgress(next);
                tvBH.setText((next - 200) + "%");
                StudioManager.setSkewBottomH(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_skewbh).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekBH.getMax(), seekBH.getProgress() + 1));
                seekBH.setProgress(next);
                tvBH.setText((next - 200) + "%");
                StudioManager.setSkewBottomH(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Shear V ──
            SeekBar seekLV = v.findViewById(R.id.seek_skewlv);
            TextView tvLV = v.findViewById(R.id.tv_skewlv_val);
            float initLV = (float) effectiveTime.optDouble("skewLeftV", 0);
            seekLV.setProgress((int) (initLV * 100 + 200));
            tvLV.setText((int) initLV + "%");
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
            v.findViewById(R.id.btn_minus_skewlv).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekLV.getMax(), seekLV.getProgress() - 1));
                seekLV.setProgress(next);
                tvLV.setText((next - 200) + "%");
                StudioManager.setSkewLeftV(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_skewlv).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekLV.getMax(), seekLV.getProgress() + 1));
                seekLV.setProgress(next);
                tvLV.setText((next - 200) + "%");
                StudioManager.setSkewLeftV(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Left Skew ──
            SeekBar seekLO = v.findViewById(R.id.seek_skewlo);
            TextView tvLO = v.findViewById(R.id.tv_skewlo_val);
            float initLO = (float) effectiveTime.optDouble("skewLeftOnly", 0);
            seekLO.setProgress((int) (initLO * 100 + 200));
            tvLO.setText((int) initLO + "%");
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
            v.findViewById(R.id.btn_minus_skewlo).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekLO.getMax(), seekLO.getProgress() - 1));
                seekLO.setProgress(next);
                tvLO.setText((next - 200) + "%");
                StudioManager.setSkewLeftOnly(requireContext(), (next - 200) / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_skewlo).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekLO.getMax(), seekLO.getProgress() + 1));
                seekLO.setProgress(next);
                tvLO.setText((next - 200) + "%");
                StudioManager.setSkewLeftOnly(requireContext(), (next - 200) / 100f);
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

            SwitchCompat swAbove = getView().findViewById(R.id.sw_date_above_mask);
            if (swAbove != null) swAbove.setChecked(d.optBoolean("aboveMask", false));

            SeekBar seekDs = getView().findViewById(R.id.seek_date_size);
            TextView tvDs = getView().findViewById(R.id.tv_date_size_val);
            int size = (int) d.optDouble("size", 40);
            if (seekDs != null) {
                seekDs.setProgress(Math.min(200, Math.max(0, size)));
                if (tvDs != null) tvDs.setText(size + "sp");
            }

            SeekBar seekDx = getView().findViewById(R.id.seek_date_x);
            TextView tvDx = getView().findViewById(R.id.tv_date_x_val);
            int x = (int) (d.optDouble("x", 0.5) * 100);
            if (seekDx != null) {
                seekDx.setProgress(Math.min(100, Math.max(0, x)));
                if (tvDx != null) tvDx.setText(x + "%");
            }

            SeekBar seekDy = getView().findViewById(R.id.seek_date_y);
            TextView tvDy = getView().findViewById(R.id.tv_date_y_val);
            int y = (int) (d.optDouble("y", 0.75) * 100);
            if (seekDy != null) {
                seekDy.setProgress(Math.min(100, Math.max(0, y)));
                if (tvDy != null) tvDy.setText(y + "%");
            }

            SeekBar seekDr = getView().findViewById(R.id.seek_date_rot);
            TextView tvDr = getView().findViewById(R.id.tv_date_rot_val);
            float rot = (float) d.optDouble("rotation", 0);
            if (seekDr != null) {
                seekDr.setProgress((int) (rot + 180));
                if (tvDr != null) tvDr.setText((int) rot + "°");
            }

            SeekBar seekDop = getView().findViewById(R.id.seek_date_opacity);
            TextView tvDop = getView().findViewById(R.id.tv_date_opacity_val);
            int dop = (int) (d.optDouble("opacity", 1.0) * 100);
            if (seekDop != null) {
                seekDop.setProgress(Math.min(100, Math.max(0, dop)));
                if (tvDop != null) tvDop.setText(dop + "%");
            }

        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_date, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;
            JSONObject effectiveDate = st.getEffectiveDate();

            // Toggle
            SwitchCompat swDate = v.findViewById(R.id.sw_date_visible);
            View dateCtrl = v.findViewById(R.id.layout_date_controls);
            boolean initVis = effectiveDate.optBoolean("visible", true);
            swDate.setChecked(initVis);
            dateCtrl.setVisibility(initVis ? View.VISIBLE : View.GONE);
            swDate.setOnCheckedChangeListener((b2, ch) -> {
                dateCtrl.setVisibility(ch ? View.VISIBLE : View.GONE);
                StudioManager.setDateVisible(requireContext(), ch);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_date_visible).setOnClickListener(b -> {
                swDate.setChecked(true);
                StudioManager.resetDateKey(requireContext(), "visible");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Date Layer: above/below mask ──
            SwitchCompat swAbove = v.findViewById(R.id.sw_date_above_mask);
            if (swAbove != null) {
                swAbove.setChecked(effectiveDate.optBoolean("aboveMask", false));
                swAbove.setOnCheckedChangeListener((b2, ch) -> {
                    StudioManager.setDateAboveMask(requireContext(), ch);
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }
            View resetAbove = v.findViewById(R.id.btn_reset_date_above_mask);
            if (resetAbove != null) {
                resetAbove.setOnClickListener(b -> {
                    swAbove.setChecked(false);
                    StudioManager.resetDateKey(requireContext(), "aboveMask");
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            // ── Date Opacity ──
            SeekBar seekDop = v.findViewById(R.id.seek_date_opacity);
            TextView tvDop = v.findViewById(R.id.tv_date_opacity_val);
            int initDop = (int) (effectiveDate.optDouble("opacity", 1.0) * 100);
            seekDop.setProgress(Math.min(100, Math.max(0, initDop)));
            tvDop.setText(initDop + "%");
            seekDop.setOnSeekBarChangeListener(simple(val -> {
                tvDop.setText(val + "%");
                StudioManager.setDateOpacity(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_date_opacity).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "opacity");
                int v2 = (int) (st.getEffectiveDate().optDouble("opacity", 1.0) * 100);
                seekDop.setProgress(Math.min(100, Math.max(0, v2)));
                tvDop.setText(v2 + "%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_date_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDop.getMax(), seekDop.getProgress() - 1));
                seekDop.setProgress(next);
                tvDop.setText(next + "%");
                StudioManager.setDateOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_date_opacity).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDop.getMax(), seekDop.getProgress() + 1));
                seekDop.setProgress(next);
                tvDop.setText(next + "%");
                StudioManager.setDateOpacity(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Date Font Size ──
            SeekBar seekDs = v.findViewById(R.id.seek_date_size);
            TextView tvDs = v.findViewById(R.id.tv_date_size_val);
            int initDs = (int) effectiveDate.optDouble("size", 40);
            seekDs.setProgress(Math.min(200, Math.max(0, initDs)));
            tvDs.setText(initDs + "sp");
            seekDs.setOnSeekBarChangeListener(simple(val -> {
                tvDs.setText(val + "sp");
                StudioManager.setDateFontSize(requireContext(), val);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_date_size).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "size");
                seekDs.setProgress(40);
                tvDs.setText("40sp");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_date_size).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDs.getMax(), seekDs.getProgress() - 1));
                seekDs.setProgress(next);
                tvDs.setText(next + "sp");
                StudioManager.setDateFontSize(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_date_size).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDs.getMax(), seekDs.getProgress() + 1));
                seekDs.setProgress(next);
                tvDs.setText(next + "sp");
                StudioManager.setDateFontSize(requireContext(), next);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Date X ──
            SeekBar seekDx = v.findViewById(R.id.seek_date_x);
            TextView tvDx = v.findViewById(R.id.tv_date_x_val);
            int initDx = (int) (effectiveDate.optDouble("x", 0.5) * 100);
            seekDx.setProgress(initDx);
            tvDx.setText(initDx + "%");
            seekDx.setOnSeekBarChangeListener(simple(val -> {
                tvDx.setText(val + "%");
                StudioManager.setDatePosX(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_date_x).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "x");
                seekDx.setProgress(50);
                tvDx.setText("50%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_date_x).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDx.getMax(), seekDx.getProgress() - 1));
                seekDx.setProgress(next);
                tvDx.setText(next + "%");
                StudioManager.setDatePosX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_date_x).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDx.getMax(), seekDx.getProgress() + 1));
                seekDx.setProgress(next);
                tvDx.setText(next + "%");
                StudioManager.setDatePosX(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Date Y ──
            SeekBar seekDy = v.findViewById(R.id.seek_date_y);
            TextView tvDy = v.findViewById(R.id.tv_date_y_val);
            int initDy = (int) (effectiveDate.optDouble("y", 0.75) * 100);
            seekDy.setProgress(initDy);
            tvDy.setText(initDy + "%");
            seekDy.setOnSeekBarChangeListener(simple(val -> {
                tvDy.setText(val + "%");
                StudioManager.setDatePosY(requireContext(), val / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_date_y).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "y");
                seekDy.setProgress(75);
                tvDy.setText("75%");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_date_y).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDy.getMax(), seekDy.getProgress() - 1));
                seekDy.setProgress(next);
                tvDy.setText(next + "%");
                StudioManager.setDatePosY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_date_y).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDy.getMax(), seekDy.getProgress() + 1));
                seekDy.setProgress(next);
                tvDy.setText(next + "%");
                StudioManager.setDatePosY(requireContext(), next / 100f);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Date Rotation ──
            SeekBar seekDr = v.findViewById(R.id.seek_date_rot);
            TextView tvDr = v.findViewById(R.id.tv_date_rot_val);
            float initDr = (float) effectiveDate.optDouble("rotation", 0);
            seekDr.setProgress((int) (initDr + 180));
            tvDr.setText((int) initDr + "°");
            seekDr.setOnSeekBarChangeListener(simple(val -> {
                float d2 = val - 180f;
                tvDr.setText((int) d2 + "°");
                StudioManager.setDateRotation(requireContext(), d2);
                st.scheduleRefresh();
                st.broadcastChange();
            }));
            v.findViewById(R.id.btn_reset_date_rot).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "rotation");
                seekDr.setProgress(180);
                tvDr.setText("0°");
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_minus_date_rot).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDr.getMax(), seekDr.getProgress() - 1));
                seekDr.setProgress(next);
                float d2 = next - 180f;
                tvDr.setText((int) d2 + "°");
                StudioManager.setDateRotation(requireContext(), d2);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_plus_date_rot).setOnClickListener(b -> {
                int next = Math.max(0, Math.min(seekDr.getMax(), seekDr.getProgress() + 1));
                seekDr.setProgress(next);
                float d2 = next - 180f;
                tvDr.setText((int) d2 + "°");
                StudioManager.setDateRotation(requireContext(), d2);
                st.scheduleRefresh();
                st.broadcastChange();
            });

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

            RadioGroup rgFmt = getView().findViewById(R.id.rg_date_format);
            if (rgFmt != null) setFmtRadio(rgFmt, d.optString("format", "EEE, dd MMM"), d.optBoolean("allCaps", false));

            RecyclerView rvDF = getView().findViewById(R.id.rv_date_fonts);
            if (rvDF != null && rvDF.getAdapter() instanceof FontPickerAdapter)
                ((FontPickerAdapter) rvDF.getAdapter()).setSelected(d.optString("font", "main3.ttf"));

            InlineColorPicker cpDate = getView().findViewById(R.id.color_picker_date);
            View swDC = getView().findViewById(R.id.swatch_date_color);
            String col = d.optString("color", "#FFFFFF");
            if (cpDate != null) cpDate.setSelectedColor(col);
            if (swDC != null) trySetBg(swDC, col);

            SwitchCompat swCaps = getView().findViewById(R.id.sw_date_allcaps);
            if (swCaps != null) swCaps.setChecked(d.optBoolean("allCaps", false));

            SwitchCompat swLow = getView().findViewById(R.id.sw_date_lowcaps);
            if (swLow != null) swLow.setChecked(d.optBoolean("lowerCase", false));
        }

        private void setFmtRadio(RadioGroup rg, String fmt, boolean allCaps) {
            if ("dd MMM yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_mmm_yyyy);
            else if ("MMM dd".equals(fmt)) rg.check(R.id.rb_fmt_mmm_dd);
            else if ("dd/MM/yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_mm_yyyy);
            else if ("MM/dd/yyyy".equals(fmt)) rg.check(R.id.rb_fmt_mm_dd_yyyy);
            else if ("EEEE".equals(fmt)) rg.check(R.id.rb_fmt_eeee);
            else if ("dd MMM".equals(fmt)) rg.check(R.id.rb_fmt_dd_mmm);
            else if ("dd MMMM yyyy".equals(fmt)) rg.check(allCaps ? R.id.rb_fmt_dd_mmmm_yyyy_caps : R.id.rb_fmt_dd_mmmm_yyyy);
            else if ("MMM dd, yyyy".equals(fmt)) rg.check(R.id.rb_fmt_mmm_dd_comma_yyyy);
            else if ("MMMM dd, yyyy".equals(fmt)) rg.check(R.id.rb_fmt_mmmm_dd_comma_yyyy);
            else if ("dd '•' MMM '•' yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_bullet_mmm_bullet_yyyy);
            else if ("dd.MM.yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_dot_mm_dot_yyyy);
            else if ("dd-MM-yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_dash_mm_dash_yyyy);
            else if ("dd-MMM-yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_dash_mmm_dash_yyyy);
            else if ("yyyy-MM-dd".equals(fmt)) rg.check(R.id.rb_fmt_yyyy_dash_mm_dash_dd);
            else if ("yyyy/MM/dd".equals(fmt)) rg.check(R.id.rb_fmt_yyyy_slash_mm_slash_dd);
            else if ("dd '|' MMM '|' yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_pipe_mmm_pipe_yyyy);
            else if ("dd '⸱' MMM '⸱' yyyy".equals(fmt)) rg.check(R.id.rb_fmt_dd_mdot_mmm_mdot_yyyy);
            else if (com.walle.wallpaper.render.ThemeRenderer.DATE_FORMAT_ORDINAL.equals(fmt)) rg.check(R.id.rb_fmt_ordinal_dd_mmmm_yyyy);
            else if ("MMM dd '•' yyyy".equals(fmt)) rg.check(R.id.rb_fmt_mmm_dd_bullet_yyyy_caps);
            else rg.check(R.id.rb_fmt_eee_dd_mmm);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
            View v = inf.inflate(R.layout.studio_page_date_settings, c, false);
            StudioFragment st = getStudio(this);
            if (st == null) return v;
            JSONObject effectiveDate = st.getEffectiveDate();

            RadioGroup rgFmt = v.findViewById(R.id.rg_date_format);
            setFmtRadio(rgFmt, effectiveDate.optString("format", "EEE, dd MMM"), effectiveDate.optBoolean("allCaps", false));
            rgFmt.setOnCheckedChangeListener((g, id) -> {
                boolean forceCaps = false;
                String fmt;
                if (id == R.id.rb_fmt_dd_mmm_yyyy) fmt = "dd MMM yyyy";
                else if (id == R.id.rb_fmt_mmm_dd) fmt = "MMM dd";
                else if (id == R.id.rb_fmt_dd_mm_yyyy) fmt = "dd/MM/yyyy";
                else if (id == R.id.rb_fmt_mm_dd_yyyy) fmt = "MM/dd/yyyy";
                else if (id == R.id.rb_fmt_eeee) fmt = "EEEE";
                else if (id == R.id.rb_fmt_dd_mmm) fmt = "dd MMM";
                else if (id == R.id.rb_fmt_dd_mmmm_yyyy) fmt = "dd MMMM yyyy";
                else if (id == R.id.rb_fmt_mmm_dd_comma_yyyy) fmt = "MMM dd, yyyy";
                else if (id == R.id.rb_fmt_mmmm_dd_comma_yyyy) fmt = "MMMM dd, yyyy";
                else if (id == R.id.rb_fmt_dd_bullet_mmm_bullet_yyyy) fmt = "dd '•' MMM '•' yyyy";
                else if (id == R.id.rb_fmt_dd_dot_mm_dot_yyyy) fmt = "dd.MM.yyyy";
                else if (id == R.id.rb_fmt_dd_dash_mm_dash_yyyy) fmt = "dd-MM-yyyy";
                // 28-JUL-2026 — the uppercase month comes from forcing all-caps.
                else if (id == R.id.rb_fmt_dd_dash_mmm_dash_yyyy) { fmt = "dd-MMM-yyyy"; forceCaps = true; }
                else if (id == R.id.rb_fmt_yyyy_dash_mm_dash_dd) fmt = "yyyy-MM-dd";
                else if (id == R.id.rb_fmt_yyyy_slash_mm_slash_dd) fmt = "yyyy/MM/dd";
                else if (id == R.id.rb_fmt_dd_pipe_mmm_pipe_yyyy) fmt = "dd '|' MMM '|' yyyy";
                else if (id == R.id.rb_fmt_dd_mdot_mmm_mdot_yyyy) fmt = "dd '⸱' MMM '⸱' yyyy";
                else if (id == R.id.rb_fmt_ordinal_dd_mmmm_yyyy) fmt = com.walle.wallpaper.render.ThemeRenderer.DATE_FORMAT_ORDINAL;
                else if (id == R.id.rb_fmt_dd_mmmm_yyyy_caps) { fmt = "dd MMMM yyyy"; forceCaps = true; }
                else if (id == R.id.rb_fmt_mmm_dd_bullet_yyyy_caps) { fmt = "MMM dd '•' yyyy"; forceCaps = true; }
                else fmt = "EEE, dd MMM";

                StudioManager.setDateFormat(requireContext(), fmt);
                if (forceCaps) {
                    // Picking a CAPS preset turns all-caps on and low-caps off, so the two
                    // case options can never both be active.
                    StudioManager.setDateAllCaps(requireContext(), true);
                    StudioManager.setDateLowerCase(requireContext(), false);
                    SwitchCompat swc = v.findViewById(R.id.sw_date_allcaps);
                    if (swc != null) swc.setChecked(true);
                    SwitchCompat swl = v.findViewById(R.id.sw_date_lowcaps);
                    if (swl != null) swl.setChecked(false);
                }
                st.scheduleRefresh();
                st.broadcastChange();
            });

            RecyclerView rvDF = v.findViewById(R.id.rv_date_fonts);
            rvDF.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            FontPickerAdapter dfa = new FontPickerAdapter(requireContext(), StudioFragment.loadedFontsList, effectiveDate.optString("font", "main3.ttf"));
            dfa.setListener(fontItem -> {
                StudioManager.setDateFont(requireContext(), fontItem.id);
                st.scheduleRefresh();
                st.broadcastChange();
            });
            rvDF.setAdapter(dfa);
            v.findViewById(R.id.btn_reset_date_fmt).setOnClickListener(b -> {
                StudioManager.resetDateKey(requireContext(), "format");
                setFmtRadio(rgFmt, st.getEffectiveDate().optString("format", "EEE, dd MMM"), st.getEffectiveDate().optBoolean("allCaps", false));
                st.scheduleRefresh();
                st.broadcastChange();
            });

            String[] dc = {effectiveDate.optString("color", "#FFFFFF")};
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
                dc[0] = st.getEffectiveDate().optString("color", "#FFFFFF");
                trySetBg(swDC, dc[0]);
                if (cpDate != null) cpDate.setSelectedColor(dc[0]);
                st.scheduleRefresh();
                st.broadcastChange();
            });

            // ── Letter case: ALL CAPS / low caps (mutually exclusive) ──
            SwitchCompat swCaps = v.findViewById(R.id.sw_date_allcaps);
            SwitchCompat swLow = v.findViewById(R.id.sw_date_lowcaps);
            swCaps.setChecked(effectiveDate.optBoolean("allCaps", false));
            if (swLow != null) swLow.setChecked(effectiveDate.optBoolean("lowerCase", false));

            swCaps.setOnCheckedChangeListener((b2, ch) -> {
                StudioManager.setDateAllCaps(requireContext(), ch);
                if (ch && swLow != null && swLow.isChecked()) {
                    // Turning ALL CAPS on switches low caps off (they'd otherwise conflict).
                    swLow.setChecked(false);
                }
                st.scheduleRefresh();
                st.broadcastChange();
            });
            v.findViewById(R.id.btn_reset_date_allcaps).setOnClickListener(b -> {
                swCaps.setChecked(false);
                StudioManager.resetDateKey(requireContext(), "allCaps");
                st.scheduleRefresh();
                st.broadcastChange();
            });

            if (swLow != null) {
                swLow.setOnCheckedChangeListener((b2, ch) -> {
                    StudioManager.setDateLowerCase(requireContext(), ch);
                    if (ch && swCaps.isChecked()) {
                        swCaps.setChecked(false);
                    }
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
                View resetLow = v.findViewById(R.id.btn_reset_date_lowcaps);
                if (resetLow != null) resetLow.setOnClickListener(b -> {
                    swLow.setChecked(false);
                    StudioManager.resetDateKey(requireContext(), "lowerCase");
                    st.scheduleRefresh();
                    st.broadcastChange();
                });
            }

            return v;
        }
    }

    private class StudioPagerAdapter extends FragmentStateAdapter {
        StudioPagerAdapter(Fragment f) {
            super(f);
        }

        @Override
        public int getItemCount() {
            return TAB_NAMES.length;
        }

        @NonNull
        @Override
        public Fragment createFragment(int pos) {
            switch (pos) {
                case 0:
                    return new BasicsPage();
                case 1:
                    return new TypographyPage();
                case 2:
                    return new EffectsPage();
                case 3:
                    return new TransformPage();
                case 4:
                    return new DatePage();
                case 5:
                    return new DateSettingsPage();
                default:
                    return new BasicsPage();
            }
        }
    }
}

