package com.walle.wallpaper.ui.wallpapers;

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
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.walle.wallpaper.R;

public class CategoryViewAllTabsFragment extends Fragment {

    private static final String ARG_CATEGORY = "arg_category";

    private final String[] tabTitles = new String[]{"All", "Premium"};
    private final int[] tabIcons = new int[]{
            R.drawable.tab2,
            R.drawable.tab1
    };

    public static CategoryViewAllTabsFragment newInstance(String category) {
        CategoryViewAllTabsFragment f = new CategoryViewAllTabsFragment();
        Bundle b = new Bundle();
        b.putString(ARG_CATEGORY, category);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_category_viewall_tabs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final String category = getArguments() != null ? getArguments().getString(ARG_CATEGORY) : null;

        TextView title = view.findViewById(R.id.category_title);
        if (CategoryAllFragment.TOKEN_PREMIUM.equals(category)) {
            // This is the global Premium collection: hide the category title entirely
            title.setText("");
            title.setVisibility(View.GONE);
        } else if (CategoryAllFragment.TOKEN_RANDOM.equals(category)) {
            title.setText(getString(R.string.random));
        } else if (category == null || category.trim().isEmpty()) {
            title.setText(getString(R.string.all));
        } else {
            title.setText(category);
        }

        ViewPager2 viewPager = view.findViewById(R.id.category_viewpager);
        TabLayout tabLayout = view.findViewById(R.id.category_tabs);
        View tabIndicator = view.findViewById(R.id.category_tab_indicator);

        tabLayout.setPadding(0, 0, 0, 0);
        tabLayout.setTabMode(TabLayout.MODE_FIXED);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                if (position == 1) {
                    return CategoryAllFragment.newInstance(category, CategoryFilter.PREMIUM);
                }
                return CategoryAllFragment.newInstance(category, CategoryFilter.ALL);
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            View tabView = LayoutInflater.from(requireContext()).inflate(R.layout.custom_tab, tabLayout, false);
            LinearLayout root = tabView.findViewById(R.id.tab_root);
            ImageView icon = tabView.findViewById(R.id.tab_icon);
            TextView text = tabView.findViewById(R.id.tab_text);

            if (position >= 0 && position < tabIcons.length) {
                icon.setImageResource(tabIcons[position]);
            }
            text.setText(tabTitles[position]);
            root.setMinimumHeight(dp(40));

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
                    com.walle.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), null);
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
            }
        });

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

        View tabView = null;
        if (tabLayout.getChildCount() > 0 && tabLayout.getChildAt(0) instanceof ViewGroup) {
            ViewGroup sliding = (ViewGroup) tabLayout.getChildAt(0);
            if (index >= 0 && index < sliding.getChildCount()) {
                tabView = sliding.getChildAt(index);
            }
        }

        if (tabView == null) {
            int width = tabLayout.getWidth();
            if (width == 0) return;
            float tabWidth = (float) width / Math.max(1, tabLayout.getTabCount());
            float targetCenter = tabWidth * index + tabWidth / 2f;
            float indicatorHalf = indicator.getWidth() / 2f;
            indicator.animate().x(targetCenter - indicatorHalf).setDuration(180).start();
            return;
        }

        float targetCenter = tabView.getLeft() + tabView.getWidth() / 2f;
        float indicatorHalf = indicator.getWidth() / 2f;
        float targetX = targetCenter - indicatorHalf;
        indicator.animate().x(targetX).setDuration(180).start();
    }
}
