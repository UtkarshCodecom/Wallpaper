package com.infinity.wallpaper.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

    private ViewPager2 viewPager;

    private final String[] tabTitles = new String[]{"Recent", "Premium", "Random"};
    private final int[] tabIcons = new int[]{
            R.drawable.ic_tab_recent,
            R.drawable.ic_tab_premium,
            R.drawable.ic_tab_random
    };

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

        WallpapersPagerAdapter adapter = new WallpapersPagerAdapter(requireActivity());
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            View tabView = LayoutInflater.from(requireContext()).inflate(R.layout.custom_tab, tabLayout, false);
            ImageView icon = tabView.findViewById(R.id.tab_icon);
            TextView text = tabView.findViewById(R.id.tab_text);

            if (position >= 0 && position < tabIcons.length) {
                icon.setImageResource(tabIcons[position]);
            }
            text.setText(tabTitles[position]);

            if (position == 0) {
                text.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
                text.setTextColor(ContextCompat.getColor(requireContext(), R.color.yellow_active));
            } else {
                text.setVisibility(View.GONE);
                icon.setVisibility(View.VISIBLE);
                icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.nav_item_inactive));
            }
            tab.setCustomView(tabView);
        }).attach();

        tabLayout.post(() -> moveTabIndicatorTo(tabLayout, tabIndicator, 0));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                View custom = tab.getCustomView();
                if (custom != null) {
                    ImageView icon = custom.findViewById(R.id.tab_icon);
                    TextView text = custom.findViewById(R.id.tab_text);
                    icon.setVisibility(View.GONE);
                    text.setVisibility(View.VISIBLE);
                    text.setTextColor(ContextCompat.getColor(requireContext(), R.color.yellow_active));
                }

                moveTabIndicatorTo(tabLayout, tabIndicator, tab.getPosition());

                // Random tab is index 2
                if (tab.getPosition() == 2) {
                    refreshRandomTab();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                View custom = tab.getCustomView();
                if (custom != null) {
                    ImageView icon = custom.findViewById(R.id.tab_icon);
                    TextView text = custom.findViewById(R.id.tab_text);
                    text.setVisibility(View.GONE);
                    icon.setVisibility(View.VISIBLE);
                    icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.nav_item_inactive));
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 2) {
                    refreshRandomTab();
                }
            }
        });
    }

    private void moveTabIndicatorTo(TabLayout tabLayout, View indicator, int index) {
        if (indicator == null || tabLayout == null) return;
        int tabCount = tabLayout.getTabCount();
        if (tabCount == 0) return;
        int width = tabLayout.getWidth();
        if (width == 0) return;
        float tabWidth = (float) width / tabCount;
        float targetCenter = tabWidth * index + tabWidth / 2f;
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
