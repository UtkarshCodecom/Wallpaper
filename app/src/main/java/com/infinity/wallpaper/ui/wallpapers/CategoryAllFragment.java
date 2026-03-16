package com.infinity.wallpaper.ui.wallpapers;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.WallpaperApplier;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.ui.AdminFragment;
import com.infinity.wallpaper.ui.common.WallpaperPreviewDialog;
import com.infinity.wallpaper.ui.common.ZigzagLoadingDialog;
import com.infinity.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CategoryAllFragment extends Fragment {

    private static final String ARG_CATEGORY = "arg_category";
    public static final String TOKEN_PREMIUM = "__PREMIUM__";
    public static final String TOKEN_RANDOM = "__RANDOM__";
    private Dialog activeDialog = null;

    public static CategoryAllFragment newInstance(String category) {
        CategoryAllFragment f = new CategoryAllFragment();
        Bundle b = new Bundle();
        b.putString(ARG_CATEGORY, category);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_category_all, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView recycler = view.findViewById(R.id.recycler_all);
        TextView emptyView = view.findViewById(R.id.empty_all);
        View header = view.findViewById(R.id.header_container);
        TextView headerTitle = view.findViewById(R.id.header_title);

        RecentWallpaperAdapter adapter = new RecentWallpaperAdapter(requireContext());
        // restore persisted selection
        adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));

        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));

        // Tap a tile → show theme picker immediately (no full-screen preview)
        adapter.setItemClickListener(item -> {
            if (!isAdded()) return;
            SelectedWallpaperStore.setSelected(requireContext(), item);
            adapter.setSelectedId(item.id);

            com.infinity.wallpaper.ui.common.ThemePickerSheet.show(requireContext(), item, (themeKey, themeJson, selectedItem) -> {
                if (!isAdded()) return;
                String bg   = selectedItem.bgUrl != null && !selectedItem.bgUrl.isEmpty() ? selectedItem.bgUrl : selectedItem.previewUrl;
                String mask = selectedItem.maskUrl;
                if (bg == null || bg.isEmpty()) return;
                Object themeObj = (themeJson != null && !themeJson.equals("{}")) ? themeJson : getFirstTheme(selectedItem);

                if (activeDialog != null) return;
                activeDialog = ZigzagLoadingDialog.show(requireContext(), "Applying…  0%");

                WallpaperApplier.prefetch(requireContext(), bg, mask, themeObj,
                        pct -> ZigzagLoadingDialog.updateMessage(activeDialog, "Applying…  " + pct + "%"),
                        (success, error) -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                dismissActiveDialog();
                                if (!success) {
                                    Toast.makeText(requireContext(), "Failed: " + (error != null ? error.getMessage() : "Unknown"), Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (WallpaperApplier.isOurLiveWallpaperActive(requireContext())) {
                                    java.io.File bgFile = new java.io.File(requireContext().getFilesDir(), "wallpaper/bg.png");
                                    WallpaperApplier.applyStaticIfPossible(requireContext(), bgFile, (ok, ex) -> {});
                                    Toast.makeText(requireContext(), "Wallpaper applied ✓", Toast.LENGTH_SHORT).show();
                                } else {
                                    WallpaperApplier.openSystemApplyScreen(requireContext());
                                }
                                AdminFragment.incrementApplyCount(selectedItem.id);
                            });
                        });
            });
        });

        // Persist selection on long press / selection change
        adapter.setSelectionListener((position, item) -> {
            SelectedWallpaperStore.setSelected(requireContext(), item);
            if (item != null) adapter.setSelectedId(item.id);
        });

        // adjust header height to 15% of screen
        int headerHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.15f);
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        lp.height = headerHeight;
        header.setLayoutParams(lp);

        // update title based on arg
        String cat = getArguments() != null ? getArguments().getString(ARG_CATEGORY) : null;
        if (cat == null) {
            cat = getString(R.string.all);
        } else if (TOKEN_RANDOM.equals(cat)) {
            headerTitle.setText(R.string.random);
        } else if (TOKEN_PREMIUM.equals(cat)) {
            // For the premium page we keep functionality but do not show the word "Premium".
            headerTitle.setText("");
        } else {
            headerTitle.setText(cat);
        }

        // collapse header on scroll: compute vertical offset dynamically so scrolling up restores header
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                int offset = rv.computeVerticalScrollOffset();
                float t = Math.min(1f, offset / (float) headerHeight);
                float scale = 1f - 0.4f * t; // shrink to 60%
                header.setPivotX(0f);
                header.setPivotY(0f);
                header.setScaleX(scale);
                header.setScaleY(scale);
            }
        });

        loadWallpapers(adapter, emptyView);
    }

    private void dismissActiveDialog() {
        ZigzagLoadingDialog.dismiss(activeDialog);
        activeDialog = null;
    }

    private void loadWallpapers(RecentWallpaperAdapter adapter, TextView emptyView) {
        adapter.setLoading(true);
        String cat = getArguments() != null ? getArguments().getString(ARG_CATEGORY) : null;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            if (!isAdded()) return;

            List<WallpaperItem> list = new ArrayList<>();
            if (task.isSuccessful() && task.getResult() != null) {
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    try {
                        WallpaperItem w = doc.toObject(WallpaperItem.class);
                        w.id = doc.getId();
                        if (TOKEN_PREMIUM.equals(cat)) {
                            if (w.isPremium) list.add(w);
                        } else if (TOKEN_RANDOM.equals(cat)) {
                            list.add(w);
                        } else {
                            if (w.category != null && w.category.equalsIgnoreCase(cat)) list.add(w);
                        }
                    } catch (Exception ex) {
                        Log.w("CategoryAllFragment", "Skipping invalid wallpaper doc", ex);
                    }
                }

                if (TOKEN_RANDOM.equals(cat)) {
                    Collections.shuffle(list);
                    if (list.size() > 50) list = list.subList(0, 50);
                }

                final List<WallpaperItem> finalList = list;
                requireActivity().runOnUiThread(() -> {
                    adapter.setItems(finalList);
                    adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                    emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } else {
                requireActivity().runOnUiThread(() -> {
                    adapter.setItems(new ArrayList<>());
                    emptyView.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private Object getFirstTheme(WallpaperItem item) {
        if (item.themes == null) return null;
        if (item.themes.containsKey("theme1")) return item.themes.get("theme1");
        for (Map.Entry<String, Object> e : item.themes.entrySet()) return e.getValue();
        return null;
    }
}
