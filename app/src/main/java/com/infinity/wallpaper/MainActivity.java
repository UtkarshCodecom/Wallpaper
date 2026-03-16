package com.infinity.wallpaper;

import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.infinity.wallpaper.ui.CollectionsFragment;
import com.infinity.wallpaper.ui.WallpapersFragment;
import com.infinity.wallpaper.ui.SettingsFragment;
import com.infinity.wallpaper.ui.StudioFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Make status and navigation bars black to match app theme
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.black));
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.black));

        BottomNavigationView navView = findViewById(R.id.top_navigation);
        View indicator = findViewById(R.id.bottom_indicator);
        FragmentManager fm = getSupportFragmentManager();
        ImageButton btnSettings = findViewById(R.id.btn_settings);

        // Track last selected item so we can restore it after opening settings
        final int[] lastSelectedItemId = {R.id.navigation_collections};

        // Load default fragment
        if (savedInstanceState == null) {
            fm.beginTransaction().replace(R.id.nav_host_fragment, new CollectionsFragment()).commit();
            navView.setSelectedItemId(R.id.navigation_collections);
            lastSelectedItemId[0] = R.id.navigation_collections;
        }

        // Position indicator under the selected item after layout pass
        navView.post(() -> moveIndicatorTo(navView, indicator, navView.getSelectedItemId()));

        navView.setOnItemSelectedListener(item -> {
            Fragment selected = null;
            int id = item.getItemId();
            // remember selection
            lastSelectedItemId[0] = id;

            if (id == R.id.navigation_collections) {
                selected = new CollectionsFragment();
            } else if (id == R.id.navigation_wallpapers) {
                selected = new WallpapersFragment();
            } else if (id == R.id.navigation_studio) {
                selected = new StudioFragment();
            }
            if (selected != null) {
                fm.beginTransaction().replace(R.id.nav_host_fragment, selected).commit();
                moveIndicatorTo(navView, indicator, id);
                return true;
            }
            return false;
        });

        // Listen for back stack changes so we can restore nav state when Settings is popped
        fm.addOnBackStackChangedListener(() -> {
            if (fm.getBackStackEntryCount() == 0) {
                // We're back to root; re-enable BottomNavigationView items and restore selection
                navView.getMenu().setGroupCheckable(0, true, true);
                // restore selection and indicator after a layout pass
                navView.post(() -> {
                    // Ensure that the previously selected item is checked visually
                    navView.setSelectedItemId(lastSelectedItemId[0]);
                    // Make indicator visible and move it
                    if (indicator != null) indicator.setVisibility(View.VISIBLE);
                    moveIndicatorTo(navView, indicator, lastSelectedItemId[0]);
                });
            }
        });

        btnSettings.setOnClickListener(v -> {
            // open settings as separate fragment in the nav host
            fm.beginTransaction().replace(R.id.nav_host_fragment, new SettingsFragment()).addToBackStack(null).commit();
            // Disable checkable state for bottom nav items to visually clear selection
            navView.getMenu().setGroupCheckable(0, false, true);
            // Move indicator off-screen after layout to avoid using an invalid selected id
            navView.post(() -> indicator.setX(-1000f));
        });
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
        // compute center x for item
        float itemWidth = (float) width / menuSize;
        float targetCenter = itemWidth * index + itemWidth / 2f;
        // adjust indicator to center under item; indicator is inside the top nav container, same coords
        float indicatorHalf = indicator.getWidth() / 2f;
        float targetX = targetCenter - indicatorHalf;

        indicator.animate().x(targetX).setDuration(200).start();
    }
}
