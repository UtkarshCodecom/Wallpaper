package com.infinity.wallpaper.ui.wallpapers;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class WallpapersPagerAdapter extends FragmentStateAdapter {

    public WallpapersPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new RecentFragment();
            case 1:
                return new PremiumFragment();
            case 2:
            default:
                return new RandomFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
