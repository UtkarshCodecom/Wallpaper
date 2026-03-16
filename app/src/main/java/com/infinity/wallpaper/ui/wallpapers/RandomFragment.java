package com.infinity.wallpaper.ui.wallpapers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.WallpaperApplier;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.ui.AdminFragment;
import com.infinity.wallpaper.ui.common.PulseRefreshView;
import com.infinity.wallpaper.ui.common.WallpaperPreviewDialog;
import com.infinity.wallpaper.ui.common.ZigzagLoadingDialog;
import com.infinity.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RandomFragment extends Fragment {

    private RecyclerView recycler;
    private RecentWallpaperAdapter adapter;
    private TextView emptyView;
    private SwipeRefreshLayout swipeRefresh;
    private View refreshIndicatorContainer;
    private PulseRefreshView pulseRefresh;
    private Dialog activeDialog = null;
    private boolean isRefreshing = false;
    private boolean isFirstLoad = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.page_random_new, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Auto-shuffle every time this tab becomes visible (skip very first load, onViewCreated handles it)
        if (!isFirstLoad && isAdded()) {
            refreshWithZigzag();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recycler = view.findViewById(R.id.recycler_random);
        emptyView = view.findViewById(R.id.empty_random);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        refreshIndicatorContainer = view.findViewById(R.id.refresh_indicator_container);
        pulseRefresh = view.findViewById(R.id.pulse_refresh);

        // Keep refresh behavior, but drop custom overlay animation
        swipeRefresh.setProgressViewOffset(false, -200, -200);
        swipeRefresh.setOnRefreshListener(this::performRefresh);

        adapter = new RecentWallpaperAdapter(requireContext());
        adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));

        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));

        // Tap on a tile → show theme picker immediately (no full-screen preview)
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
                                    String msg = error != null ? error.getMessage() : "Unknown error";
                                    Toast.makeText(requireContext(), "Failed: " + msg, Toast.LENGTH_LONG).show();
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

        loadWallpapers();
    }

    private void dismissActiveDialog() {
        ZigzagLoadingDialog.dismiss(activeDialog);
        activeDialog = null;
    }

    private Object getFirstTheme(WallpaperItem item) {
        if (item.themes == null) return null;
        if (item.themes.containsKey("theme1")) return item.themes.get("theme1");
        for (Map.Entry<String, Object> e : item.themes.entrySet()) return e.getValue();
        return null;
    }

    /** Public method to refresh content - called from parent when tab is selected */
    public void refreshContent() {
        if (!isAdded() || isRefreshing) return;
        if (isFirstLoad) {
            isFirstLoad = false;
            return; // Skip first load as onViewCreated already loads
        }
        refreshWithZigzag();
    }

    private void refreshWithZigzag() {
        // Simplified: just trigger data reload, no overlay animation
        if (isRefreshing) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        isRefreshing = true;
        performRefresh();
    }

    private void showRefreshIndicator() {
        // No-op: UI overlay removed but method kept for compatibility
    }

    private void hideRefreshIndicator() {
        // No-op: UI overlay removed but method kept for compatibility
    }

    private void animateRefreshComplete() {
        if (!isAdded()) return;
        if (recycler != null) {
            recycler.setTranslationY(0f);
            recycler.setAlpha(1f);
        }
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        isRefreshing = false;
    }

    private void performRefresh() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            if (!isAdded()) return;

            if (task.isSuccessful() && task.getResult() != null) {
                List<WallpaperItem> list = new ArrayList<>();
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    WallpaperItem w = doc.toObject(WallpaperItem.class);
                    w.id = doc.getId();
                    list.add(w);
                }
                // Shuffle for random order
                Collections.shuffle(list);
                if (list.size() > 50) list = list.subList(0, 50);

                final List<WallpaperItem> finalList = list;
                requireActivity().runOnUiThread(() -> {
                    adapter.setItems(finalList);
                    adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                    emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                    animateRefreshComplete();
                });
            } else {
                requireActivity().runOnUiThread(() -> {
                    animateRefreshComplete();
                    Toast.makeText(requireContext(), "Failed to refresh", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadWallpapers() {
        adapter.setLoading(true);
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            if (!isAdded()) return;

            if (task.isSuccessful() && task.getResult() != null) {
                List<WallpaperItem> list = new ArrayList<>();
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    WallpaperItem w = doc.toObject(WallpaperItem.class);
                    w.id = doc.getId();
                    list.add(w);
                }
                // Shuffle for random order
                Collections.shuffle(list);
                if (list.size() > 50) list = list.subList(0, 50);

                final List<WallpaperItem> finalList = list;
                requireActivity().runOnUiThread(() -> {
                    adapter.setItems(finalList);
                    adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                    emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                    isFirstLoad = false;
                });
            } else {
                requireActivity().runOnUiThread(() -> {
                    adapter.setItems(new ArrayList<>());
                    emptyView.setVisibility(View.VISIBLE);
                });
            }
        });
    }
}
