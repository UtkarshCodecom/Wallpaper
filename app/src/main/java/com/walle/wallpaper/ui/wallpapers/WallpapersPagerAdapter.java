package com.walle.wallpaper.ui.wallpapers;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class WallpapersPagerAdapter extends FragmentStateAdapter {

    public WallpapersPagerAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return CategoryAllFragment.newInstance(CategoryAllFragment.TOKEN_FAVORITES);
            case 1:
                return new RecentFragment();
            case 2:
                return new PremiumFragment();
            case 3:
            default:
                return new RandomFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 4;
    }
}
