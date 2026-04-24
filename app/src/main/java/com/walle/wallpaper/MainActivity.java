package com.walle.wallpaper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.walle.wallpaper.ui.CollectionsFragment;
import com.walle.wallpaper.ui.SettingsFragment;
import com.walle.wallpaper.ui.StudioFragment;
import com.walle.wallpaper.ui.WallpapersFragment;

public class MainActivity extends AppCompatActivity {

    // Left mini panel shrinks to this scale
    private static final float MINI_SCALE = 0.58f;
    private static final float SETTINGS_LEFT_FRAC = 0.4f;
    private static final long ANIM_MS = 300L;
    // How far you must drag (fraction of screen) to trigger a snap
    private static final float SNAP_THRESHOLD = 0.18f;

    private boolean settingsOpen = false;

    private int lastMainItemId = R.id.navigation_collections;

    private View mainPanel;
    private View settingsPanel;
    private View dragStrip;

    private View splitContainer;
    private View topNavContainer;
    private View stickersContainer;
    private View appSwitcherMenu;

    private View navWallpaperBtn, navStickersBtn;
    private ImageView navWallpaperIcon, navStickersIcon;
    private TextView navWallpaperText, navStickersText;

    // Touch state
    private float downRawX;
    private float downRawY;
    private float downSettingsTx;
    private boolean dragStarted;
    private int touchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof SecurityException && e.getMessage() != null && e.getMessage().contains("com.google.android.gms")) {
                // Ignore GMS internal SecurityException
                return;
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.black));
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.black));

        BottomNavigationView navView = findViewById(R.id.top_navigation);
        View indicator = findViewById(R.id.bottom_indicator);
        mainPanel = findViewById(R.id.nav_host_fragment);
        settingsPanel = findViewById(R.id.settings_panel);
        dragStrip = findViewById(R.id.settings_drag_strip);

        FragmentManager fm = getSupportFragmentManager();

        Runnable initFragments = () -> {
            if (savedInstanceState == null && fm.findFragmentById(R.id.nav_host_fragment) == null) {
                fm.beginTransaction()
                        .replace(R.id.nav_host_fragment, new WallpapersFragment())
                        .commitAllowingStateLoss();
                navView.setSelectedItemId(R.id.navigation_wallpapers);
            }
            navView.post(() -> moveIndicatorTo(navView, indicator, navView.getSelectedItemId()));
        };

        com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            initFragments.run();
        } else {
            auth.signInAnonymously()
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            android.util.Log.d("MainActivity", "signInAnonymously:success");
                            // Toast.makeText(this, "Auth Success!", Toast.LENGTH_SHORT).show();
                        } else {
                            android.util.Log.w("MainActivity", "signInAnonymously:failure", task.getException());
                            android.widget.Toast.makeText(this, "Firebase Auth Failed! Enable Anonymous Sign-in in Firebase Console.", android.widget.Toast.LENGTH_LONG).show();
                        }
                        initFragments.run();
                    });
        }

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        splitContainer = findViewById(R.id.split_container);
        topNavContainer = findViewById(R.id.top_nav_container);
        stickersContainer = findViewById(R.id.stickers_container);
        appSwitcherMenu = findViewById(R.id.app_switcher_menu);

        navWallpaperBtn = findViewById(R.id.nav_app_wallpaper_btn);
        navStickersBtn = findViewById(R.id.nav_app_stickers_btn);

        navWallpaperIcon = findViewById(R.id.nav_app_wallpaper_icon);
        navWallpaperText = findViewById(R.id.nav_app_wallpaper_text);
        navStickersIcon = findViewById(R.id.nav_app_stickers_icon);
        navStickersText = findViewById(R.id.nav_app_stickers_text);

        BottomNavigationView stickersNavView = findViewById(R.id.stickers_top_navigation);
        View stickersIndicator = findViewById(R.id.stickers_bottom_indicator);

        stickersNavView.setOnItemSelectedListener(item -> {
            moveIndicatorTo(stickersNavView, stickersIndicator, item.getItemId());
            return true;
        });

        // Set default indicators for stickers
        stickersNavView.post(() -> moveIndicatorTo(stickersNavView, stickersIndicator, stickersNavView.getSelectedItemId()));

        View.OnClickListener switchListener = v -> {
            boolean isWallpaper = v.getId() == R.id.nav_app_wallpaper_btn;

            stickersContainer.setVisibility(isWallpaper ? View.GONE : View.VISIBLE);
            splitContainer.setVisibility(isWallpaper ? View.VISIBLE : View.GONE);
            topNavContainer.setVisibility(isWallpaper ? View.VISIBLE : View.GONE);

            navWallpaperBtn.setBackgroundResource(isWallpaper ? R.drawable.bg_tabs_segment_selected : android.R.color.transparent);
            navWallpaperIcon.setVisibility(isWallpaper ? View.GONE : View.VISIBLE);
            navWallpaperText.setVisibility(isWallpaper ? View.VISIBLE : View.GONE);

            navStickersBtn.setBackgroundResource(isWallpaper ? android.R.color.transparent : R.drawable.bg_tabs_segment_selected);
            navStickersIcon.setVisibility(isWallpaper ? View.VISIBLE : View.GONE);
            navStickersText.setVisibility(isWallpaper ? View.GONE : View.VISIBLE);

            if (!isWallpaper) {
                stickersNavView.post(() -> moveIndicatorTo(stickersNavView, stickersIndicator, stickersNavView.getSelectedItemId()));
            }
        };

        navWallpaperBtn.setOnClickListener(switchListener);
        navStickersBtn.setOnClickListener(switchListener);

        // ── Nav selection ─────────────────────────────────────────────────
        navView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();


            Fragment selected = null;
            if (id == R.id.navigation_collections) {
                selected = new CollectionsFragment();
                appSwitcherMenu.setVisibility(View.GONE);
            } else if (id == R.id.navigation_wallpapers) {
                selected = new WallpapersFragment();
                appSwitcherMenu.setVisibility(View.VISIBLE);
            } else if (id == R.id.navigation_studio) {
                selected = new StudioFragment();
                appSwitcherMenu.setVisibility(View.GONE);
                com.walle.wallpaper.ui.common.AdManager.showInterstitial(MainActivity.this, null);
            } else if (id == R.id.navigation_settings) {
                selected = new SettingsFragment();
                appSwitcherMenu.setVisibility(View.GONE);
            }

            if (selected != null) {
                lastMainItemId = id;
                fm.beginTransaction().replace(R.id.nav_host_fragment, selected).commit();
                moveIndicatorTo(navView, indicator, id);
                return true;
            }
            return false;
        });

        // ── Tap mini main panel → close settings ─────────────────────────
        mainPanel.setOnClickListener(v -> {
            if (settingsOpen) {
                closeSettings(fm, true);
                navView.setSelectedItemId(lastMainItemId);
                moveIndicatorTo(navView, indicator, lastMainItemId);
            }
        });

        // When settings is open, the main panel is visually scaled but still full-width.
        // We prevent it from intercepting touches by turning off clickability/focusability.
        mainPanel.setClickable(true);
        mainPanel.setFocusable(true);

        // ── Drag settings panel (left strip only) ───────────────────────
        // This keeps the settings content (including Admin panel) fully touchable.
        if (dragStrip != null) {
            dragStrip.setOnTouchListener((v, event) -> {
                if (!settingsOpen) return false;

                switch (event.getActionMasked()) {

                    case MotionEvent.ACTION_DOWN: {
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downSettingsTx = settingsPanel.getTranslationX();
                        dragStarted = false;
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        handleSettingsDragMove(dx, dy);
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!dragStarted) {
                            v.performClick();
                            return true;
                        }
                        handleSettingsDragEndDeferred(fm, navView, indicator, event);
                        return true;
                    }
                }
                return false;
            });
        }
    }

    // ── Open settings (split view) ────────────────────────────────────────

    private void openSettings(FragmentManager fm, BottomNavigationView navView, View indicator) {
        settingsOpen = true;

        // Disable main panel input while settings is open (prevents overlap/touch issues)
        mainPanel.setClickable(false);
        mainPanel.setFocusable(false);

        // initialize downRawY baseline for direction detection
        downRawY = 0f;

        fm.beginTransaction()
                .replace(R.id.settings_fragment_container, new SettingsFragment())
                .commit();

        settingsPanel.setVisibility(View.VISIBLE);
        moveIndicatorTo(navView, indicator, R.id.navigation_settings);

        float screenW = getScreenWidth();
        float splitLandX = screenW * SETTINGS_LEFT_FRAC;

        // Start: settings fully off-screen right
        settingsPanel.setTranslationX(screenW);

        // Pivot main panel at its left edge, vertical center
        mainPanel.post(() -> {
            mainPanel.setPivotX(0f);
            mainPanel.setPivotY(mainPanel.getHeight() / 2f);
        });

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mainPanel, View.SCALE_X, 1f, MINI_SCALE);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mainPanel, View.SCALE_Y, 1f, MINI_SCALE);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mainPanel, View.ALPHA, 1f, 0.45f);
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(settingsPanel, View.TRANSLATION_X,
                screenW, splitLandX);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha, slideIn);
        set.setDuration(ANIM_MS);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Keep main panel input disabled while settings is open.
            }
        });
        set.start();
    }

    // ── Animate to fullscreen ─────────────────────────────────────────────

    private void animateToFullscreen() {
        mainPanel.setClickable(false);

        float currentTx = settingsPanel.getTranslationX();

        ObjectAnimator slideLeft = ObjectAnimator.ofFloat(settingsPanel,
                View.TRANSLATION_X, currentTx, 0f);
        ObjectAnimator fadeMain = ObjectAnimator.ofFloat(mainPanel,
                View.ALPHA, mainPanel.getAlpha(), 0f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(slideLeft, fadeMain);
        set.setDuration(ANIM_MS);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
            }
        });
        set.start();
    }

    // ── Animate back to split position ────────────────────────────────────

    private void animateToSplit() {
        float screenW = getScreenWidth();
        float splitLandX = screenW * SETTINGS_LEFT_FRAC;
        float currentTx = settingsPanel.getTranslationX();

        ObjectAnimator slideBack = ObjectAnimator.ofFloat(settingsPanel,
                View.TRANSLATION_X, currentTx, splitLandX);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mainPanel, View.SCALE_X,
                mainPanel.getScaleX(), MINI_SCALE);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mainPanel, View.SCALE_Y,
                mainPanel.getScaleY(), MINI_SCALE);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mainPanel, View.ALPHA,
                mainPanel.getAlpha(), 0.45f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(slideBack, scaleX, scaleY, alpha);
        set.setDuration(200);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mainPanel.setClickable(true);
            }
        });
        set.start();
    }

    // ── Close settings ────────────────────────────────────────────────────

    private void closeSettings(FragmentManager fm, boolean animate) {
        settingsOpen = false;

        // Restore main panel input
        mainPanel.setClickable(true);
        mainPanel.setFocusable(true);

        // No layout-width shrinking; keep layout stable to avoid visual overlap glitches.

        float screenW = getScreenWidth();

        if (!animate) {
            mainPanel.setScaleX(1f);
            mainPanel.setScaleY(1f);
            mainPanel.setAlpha(1f);
            settingsPanel.setTranslationX(screenW);
            settingsPanel.setVisibility(View.GONE);
            removeSettingsFragment(fm);
            return;
        }

        float currentTx = settingsPanel.getTranslationX();

        ObjectAnimator slideOut = ObjectAnimator.ofFloat(settingsPanel,
                View.TRANSLATION_X, currentTx, screenW);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mainPanel, View.SCALE_X,
                mainPanel.getScaleX(), 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mainPanel, View.SCALE_Y,
                mainPanel.getScaleY(), 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mainPanel, View.ALPHA,
                mainPanel.getAlpha(), 1f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(slideOut, scaleX, scaleY, alpha);
        set.setDuration(ANIM_MS);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settingsPanel.setVisibility(View.GONE);
                removeSettingsFragment(fm);
            }
        });
        set.start();
    }

    // ── Touch area helpers ────────────────────────────────────────────────

    // We no longer shrink layout width; it caused content overlap.
    private void shrinkMainPanelTouchArea(int widthPx) {
        // no-op
    }

    private void restoreMainPanelTouchArea() {
        // no-op
    }

    private void removeSettingsFragment(FragmentManager fm) {
        Fragment sf = fm.findFragmentById(R.id.settings_fragment_container);
        if (sf != null) fm.beginTransaction().remove(sf).commitAllowingStateLoss();
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private float getScreenWidth() {
        View container = findViewById(R.id.split_container);
        int w = container.getWidth();
        return w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
    }

    private void moveIndicatorTo(BottomNavigationView navView, View indicator, int itemId) {
        if (indicator == null || navView == null) return;
        int menuSize = navView.getMenu().size();
        int index = 0;
        for (int i = 0; i < menuSize; i++) {
            if (navView.getMenu().getItem(i).getItemId() == itemId) {
                index = i;
                break;
            }
        }
        int width = navView.getWidth();
        if (width == 0) return;
        float itemW = (float) width / menuSize;
        float center = itemW * index + itemW / 2f;
        float half = indicator.getWidth() / 2f;
        indicator.animate().x(center - half).setDuration(200).start();
    }

    /**
     * Finds the top-most child view under the given coordinates inside the settings panel.
     */
    private View findTopChildUnder(View root, float x, float y) {
        if (!(root instanceof ViewGroup)) return root;
        ViewGroup vg = (ViewGroup) root;

        for (int i = vg.getChildCount() - 1; i >= 0; i--) {
            View child = vg.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;

            float cx = x - child.getLeft();
            float cy = y - child.getTop();

            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                if (child instanceof ViewGroup) {
                    View deeper = findTopChildUnder(child, cx, cy);
                    return deeper != null ? deeper : child;
                }
                return child;
            }
        }
        return null;
    }

    private boolean isInteractiveChild(View v) {
        if (v == null) return false;
        if (v.isClickable() || v.isLongClickable() || v.isFocusable()) return true;
        if (v instanceof SeekBar) return true;
        if (v instanceof SwitchCompat) return true;
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)
                || v.canScrollVertically(1) || v.canScrollVertically(-1)) return true;
        return false;
    }

    private void handleSettingsDragMove(float dx, float dy) {
        if (!dragStarted) {
            if (Math.abs(dx) < touchSlop || Math.abs(dx) < Math.abs(dy)) return;
            dragStarted = true;
        }

        float screenW = getScreenWidth();
        float splitLandX = screenW * SETTINGS_LEFT_FRAC;

        float newTx = Math.max(0f, Math.min(screenW, downSettingsTx + dx));
        settingsPanel.setTranslationX(newTx);

        if (newTx >= splitLandX) {
            float closeFrac = (newTx - splitLandX) / (screenW - splitLandX);
            closeFrac = Math.max(0f, Math.min(1f, closeFrac));
            float s = MINI_SCALE + (1f - MINI_SCALE) * closeFrac;
            float a = 0.45f + (1f - 0.45f) * closeFrac;
            mainPanel.setScaleX(s);
            mainPanel.setScaleY(s);
            mainPanel.setAlpha(a);
        } else {
            mainPanel.setScaleX(MINI_SCALE);
            mainPanel.setScaleY(MINI_SCALE);
            mainPanel.setAlpha(0.2f);
        }
    }

    // ── Deferred closeSettings() to prevent input-dispatch crashes ────────

    private void handleSettingsDragEndDeferred(FragmentManager fm, BottomNavigationView navView, View indicator, MotionEvent event) {
        // MotionEvent objects are recycled; copy the needed values now.
        final float dx = event.getRawX() - downRawX;
        settingsPanel.post(() -> handleSettingsDragEnd(fm, navView, indicator, dx));
    }

    private void handleSettingsDragEnd(FragmentManager fm, BottomNavigationView navView, View indicator, float dx) {
        float screenW = getScreenWidth();

        if (dx < -(screenW * SNAP_THRESHOLD)) {
            animateToFullscreen();
        } else if (dx > screenW * SNAP_THRESHOLD) {
            closeSettings(fm, true);
            navView.setSelectedItemId(lastMainItemId);
            moveIndicatorTo(navView, indicator, lastMainItemId);
        } else {
            animateToSplit();
        }

        dragStarted = false;
    }

    private void switchAppTab(boolean isWallpaper) {
        if (isWallpaper) {
            stickersContainer.setVisibility(View.GONE);
            splitContainer.setVisibility(View.VISIBLE);
            topNavContainer.setVisibility(View.VISIBLE);

            navWallpaperBtn.setBackgroundResource(R.drawable.bg_tabs_segment_selected);
            navWallpaperIcon.setVisibility(View.GONE);
            navWallpaperText.setVisibility(View.VISIBLE);

            navStickersBtn.setBackgroundResource(android.R.color.transparent);
            navStickersIcon.setVisibility(View.VISIBLE);
            navStickersText.setVisibility(View.GONE);
        } else {
            stickersContainer.setVisibility(View.VISIBLE);
            splitContainer.setVisibility(View.GONE);
            topNavContainer.setVisibility(View.GONE);

            navStickersBtn.setBackgroundResource(R.drawable.bg_tabs_segment_selected);
            navStickersIcon.setVisibility(View.GONE);
            navStickersText.setVisibility(View.VISIBLE);

            navWallpaperBtn.setBackgroundResource(android.R.color.transparent);
            navWallpaperIcon.setVisibility(View.VISIBLE);
            navWallpaperText.setVisibility(View.GONE);
        }
    }
}
