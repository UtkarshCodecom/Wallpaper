package com.infinity.wallpaper.ui;

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
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.ui.wallpapers.RandomFragment;
import com.infinity.wallpaper.ui.wallpapers.WallpapersPagerAdapter;

public class WallpapersFragment extends Fragment {

    private final String[] tabTitles = new String[]{"Recent", "Premium", "Random"};
    private final int[] tabIcons = new int[]{
            R.drawable.tab2,
            R.drawable.tab3,
            R.drawable.tab1
    };
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

            if (position == 0) {
                applyTabSelected(root, icon, text);
            } else {
                applyTabUnselected(root, icon, text);
            }
            tab.setCustomView(tabView);
        }).attach();

        tabLayout.post(() -> moveTabIndicatorTo(tabLayout, tabIndicator, 0));

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

                if (tab.getPosition() == 1) {
                    com.infinity.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), null);
                }

                if (tab.getPosition() == 2) {
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
                if (tab.getPosition() == 2) {
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
        if (root != null) root.setBackgroundResource(R.drawable.bg_tabs_segment_selected);
    }

    private void applyTabUnselected(@Nullable View root, @NonNull ImageView icon, @NonNull TextView text) {
        text.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.nav_item_inactive));
        if (root != null) root.setBackgroundResource(android.R.color.transparent);
    }

    private int dp(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void moveTabIndicatorTo(TabLayout tabLayout, View indicator, int index) {
        if (indicator == null || tabLayout == null) return;
        if (tabLayout.getTabCount() == 0) return;

        // Prefer the actual tab view geometry for perfect alignment
        View tabView = null;
        if (tabLayout.getChildCount() > 0 && tabLayout.getChildAt(0) instanceof ViewGroup) {
            ViewGroup sliding = (ViewGroup) tabLayout.getChildAt(0);
            if (index >= 0 && index < sliding.getChildCount()) {
                tabView = sliding.getChildAt(index);
            }
        }

        if (tabView == null) {
            // Fallback to equal width
            int width = tabLayout.getWidth();
            if (width == 0) return;
            float tabWidth = (float) width / Math.max(1, tabLayout.getTabCount());
            float targetCenter = tabWidth * index + tabWidth / 2f;
            float indicatorHalf = indicator.getWidth() / 2f;
            indicator.animate().x(targetCenter - indicatorHalf).setDuration(180).start();
            return;
        }

        // Compute indicator X inside the same parent coordinates (tabLayout)
        float targetCenter = tabView.getLeft() + tabView.getWidth() / 2f;
        float indicatorHalf = indicator.getWidth() / 2f;
        float targetX = targetCenter - indicatorHalf;
        indicator.animate().x(targetX).setDuration(180).start();
    }

    private void refreshRandomTab() {
        if (viewPager == null) return;

        Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + 2);
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
