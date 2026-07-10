package com.walle.wallpaper.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.walle.wallpaper.R;
import com.walle.wallpaper.data.Banner;
import com.walle.wallpaper.ui.wallpapers.RandomFragment;
import com.walle.wallpaper.ui.wallpapers.WallpapersPagerAdapter;
import com.walle.wallpaper.ui.widgets.BannerView;

import java.util.ArrayList;
import java.util.List;

public class WallpapersFragment extends Fragment {

    private final String[] tabTitles = new String[]{"Favorites", "Recent", "Premium", "Surprise me"};
    private final int[] tabIcons = new int[]{
            R.drawable.ic_heart_filled,
            R.drawable.tab2,
            R.drawable.tab3,
            R.drawable.tab1
    };
    private static final int DEFAULT_TAB = 1; // Recent (Favorites sits to its left)
    private ViewPager2 viewPager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wallpapers, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewPager = view.findViewById(R.id.view_pager);
        TabLayout tabLayout = view.findViewById(R.id.tab_layout);
        View tabIndicator = view.findViewById(R.id.tab_indicator);

        loadBanners(view.findViewById(R.id.home_banner));

        // inside the shared oval container, we don't want extra padding
        tabLayout.setPadding(0, 0, 0, 0);
        tabLayout.setTabMode(TabLayout.MODE_FIXED);

        WallpapersPagerAdapter adapter = new WallpapersPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            View tabView = LayoutInflater.from(requireContext()).inflate(R.layout.custom_tab, tabLayout, false);
            LinearLayout root = tabView.findViewById(R.id.tab_root);
            ImageView icon = tabView.findViewById(R.id.tab_icon);
            TextView text = tabView.findViewById(R.id.tab_text);

            if (position >= 0 && position < tabIcons.length) {
                icon.setImageResource(tabIcons[position]);

            }
            text.setText(tabTitles[position]);

            // ensure the tab segment fills available height
            root.setMinimumHeight(dp(30));

            if (position == DEFAULT_TAB) {
                applyTabSelected(root, icon, text);
            } else {
                applyTabUnselected(root, icon, text);
            }
            tab.setCustomView(tabView);
        }).attach();

        // Open on Recent by default (Favorites is the left-most tab).
        viewPager.setCurrentItem(DEFAULT_TAB, false);

        // Red glow around the whole tab bar oval (elevation shadow tinted accent, following
        // the bg_nav_oval outline). Clip is disabled up the chain so it isn't cut off.
        View tabsContainer = view.findViewById(R.id.tabs_container);
        if (tabsContainer != null) {
            tabsContainer.setElevation(dp(9));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int accent = ContextCompat.getColor(requireContext(), R.color.accent);
                tabsContainer.setOutlineSpotShadowColor(accent);
                tabsContainer.setOutlineAmbientShadowColor(accent);
            }
            disableClipUp(tabsContainer, view);
        }

        tabLayout.post(() -> moveTabIndicatorTo(tabLayout, tabIndicator, DEFAULT_TAB));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                View custom = tab.getCustomView();
                if (custom != null) {
                    LinearLayout root = custom.findViewById(R.id.tab_root);
                    ImageView icon = custom.findViewById(R.id.tab_icon);
                    TextView text = custom.findViewById(R.id.tab_text);
                    applyTabSelected(root, icon, text);
                }

                moveTabIndicatorTo(tabLayout, tabIndicator, tab.getPosition());

                if (tab.getPosition() == 2) { // Premium
                    com.walle.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), null);
                }

                if (tab.getPosition() == 3) { // Random
                    refreshRandomTab();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                View custom = tab.getCustomView();
                if (custom != null) {
                    LinearLayout root = custom.findViewById(R.id.tab_root);
                    ImageView icon = custom.findViewById(R.id.tab_icon);
                    TextView text = custom.findViewById(R.id.tab_text);
                    applyTabUnselected(root, icon, text);
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 3) {
                    refreshRandomTab();
                }
            }
        });

        // Also realign the indicator while ViewPager is settling/swiping
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (tabLayout.getTabAt(position) != null) {
                    moveTabIndicatorTo(tabLayout, tabIndicator, position);
                }
            }
        });
    }

    private void applyTabSelected(@Nullable View root, @NonNull ImageView icon, @NonNull TextView text) {
        icon.setVisibility(View.GONE);
        text.setVisibility(View.VISIBLE);
        text.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent));
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_tabs_segment_selected);
        }
    }

    private void applyTabUnselected(@Nullable View root, @NonNull ImageView icon, @NonNull TextView text) {
        text.setVisibility(View.GONE);
        text.setShadowLayer(0f, 0f, 0f, 0);
        icon.setVisibility(View.VISIBLE);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.nav_item_inactive));
        if (root != null) {
            root.setBackgroundResource(android.R.color.transparent);
        }
    }

    private void disableClip(@Nullable ViewGroup vg) {
        if (vg == null) return;
        vg.setClipChildren(false);
        vg.setClipToPadding(false);
    }

    /** Disable clipping on {@code from} and every ancestor up to and including {@code root}. */
    private void disableClipUp(@Nullable View from, @Nullable View root) {
        View v = from;
        while (v instanceof ViewGroup) {
            disableClip((ViewGroup) v);
            if (v == root || !(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
    }

    private int dp(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void moveTabIndicatorTo(TabLayout tabLayout, View indicator, int index) {
        if (indicator == null || tabLayout == null) return;
        if (tabLayout.getTabCount() == 0) return;

        // Prefer the actual tab view geometry for perfect alignment
        ViewGroup sliding = null;
        View tabView = null;
        if (tabLayout.getChildCount() > 0 && tabLayout.getChildAt(0) instanceof ViewGroup) {
            sliding = (ViewGroup) tabLayout.getChildAt(0);
            if (index >= 0 && index < sliding.getChildCount()) {
                tabView = sliding.getChildAt(index);
            }
        }

        if (tabView == null) {
            // Fallback to equal width
            int width = tabLayout.getWidth();
            if (width == 0) return;
            float tabWidth = (float) width / Math.max(1, tabLayout.getTabCount());
            float targetCenter = tabLayout.getX() + tabWidth * index + tabWidth / 2f;
            float indicatorHalf = indicator.getWidth() / 2f;
            indicator.animate().x(targetCenter - indicatorHalf).setDuration(180).start();
            return;
        }

        // tabView.getLeft() is relative to the sliding strip; add the strip's and the
        // TabLayout's own offset so the centre is in the indicator's parent coordinates.
        float center = tabLayout.getX() + sliding.getX() + tabView.getLeft() + tabView.getWidth() / 2f;
        float indicatorHalf = indicator.getWidth() / 2f;
        indicator.animate().x(center - indicatorHalf).setDuration(180).start();
    }

    private void loadBanners(BannerView bannerView) {
        if (bannerView == null) return;
        FirebaseFirestore.getInstance().collection("banners").get()
                .addOnSuccessListener(snap -> {
                    if (!isAdded()) return;
                    List<Banner> list = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            Banner b = doc.toObject(Banner.class);
                            b.id = doc.getId();
                            if (b.imageUrl != null && !b.imageUrl.trim().isEmpty()) list.add(b);
                        } catch (Exception ignored) {
                        }
                    }
                    bannerView.setBanners(list);
                });
    }

    private void refreshRandomTab() {
        if (viewPager == null) return;

        Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + 3);
        if (fragment instanceof RandomFragment) {
            ((RandomFragment) fragment).refreshContent();
            return;
        }

        for (Fragment f : getChildFragmentManager().getFragments()) {
            if (f instanceof RandomFragment) {
                ((RandomFragment) f).refreshContent();
                return;
            }
        }
    }
}
