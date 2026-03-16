package com.infinity.wallpaper.ui.wallpapers;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.infinity.wallpaper.R;

public class PremiumFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.page_premium, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // embed CategoryAllFragment to reuse list UI
        getChildFragmentManager().beginTransaction()
                .replace(R.id.child_fragment_container, CategoryAllFragment.newInstance(CategoryAllFragment.TOKEN_PREMIUM))
                .commit();
    }
}
