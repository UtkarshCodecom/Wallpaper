package com.infinity.wallpaper.ui.wallpapers;

import android.app.Dialog;
import android.os.Bundle;
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
import com.infinity.wallpaper.ui.common.PullAwareSwipeRefreshLayout;
import com.infinity.wallpaper.ui.common.PullRevealRefreshController;
import com.infinity.wallpaper.ui.common.PulseRefreshView;
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
    private PullAwareSwipeRefreshLayout swipeRefresh;
    private View refreshIndicatorContainer;
    private PulseRefreshView pulseRefresh;
    private Dialog activeDialog = null;
    private boolean isRefreshing = false;
    private boolean isFirstLoad = true;
    private PullRevealRefreshController refreshController;

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

        // Keep refresh behavior, but drop default spinner
        swipeRefresh.setProgressViewOffset(false, -200, -200);

        refreshController = new PullRevealRefreshController(swipeRefresh, refreshIndicatorContainer, pulseRefresh);
        refreshController.setOnRefresh(this::performRefresh);

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
                String bg = selectedItem.bgUrl != null && !selectedItem.bgUrl.isEmpty() ? selectedItem.bgUrl : selectedItem.previewUrl;
                String mask = selectedItem.maskUrl;
                if (bg == null || bg.isEmpty()) return;
                Object themeObj = (themeJson != null && !themeJson.equals("{}")) ? themeJson : getFirstTheme(selectedItem);

                if (activeDialog != null) return;
                activeDialog = ZigzagLoadingDialog.show(requireContext(), "Applying… 0%");

                WallpaperApplier.prefetch(requireContext(), bg, mask, themeObj,
                        pct -> ZigzagLoadingDialog.updateMessage(activeDialog, "Applying…  " + pct + "%"),
                        (success, error) -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!success) {
                                    dismissActiveDialog();
                                    String msg = error != null ? error.getMessage() : "Unknown error";
                                    Toast.makeText(requireContext(), "Failed: " + msg, Toast.LENGTH_LONG).show();
                                    return;
                                }

                                // Show Ad immediately after prefetch succeeds
                                com.infinity.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), () -> {
                                    // When ad finishes, check if we need to open the screen
                                    if (WallpaperApplier.isOurLiveWallpaperActive(requireContext())) {
                                        dismissActiveDialog();
                                        Toast.makeText(requireContext(), "Wallpaper applied successfully", Toast.LENGTH_SHORT).show();
                                    } else {
                                        dismissActiveDialog();
                                        WallpaperApplier.openSystemApplyScreen(requireContext());
                                    }
                                });
                                AdminFragment.incrementApplyCount(selectedItem.id);
                            });
                        });
            });
        });

        // Kick off an initial refresh animation on first open
        refreshWithZigzag();
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

    /**
     * Public method to refresh content - called from parent when tab is selected
     */
    public void refreshContent() {
        if (!isAdded() || isRefreshing) return;
        if (isFirstLoad) {
            isFirstLoad = false;
            return; // Skip first load as onViewCreated already loads
        }
        refreshWithZigzag();
    }

    private void refreshWithZigzag() {
        if (isRefreshing) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            if (refreshController != null) refreshController.finish();
            return;
        }
        isRefreshing = true;
        // Trigger SwipeRefreshLayout refresh (starts pulse on release behavior)
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        performRefresh();
    }

    private void animateRefreshComplete() {
        if (!isAdded()) return;
        if (refreshController != null) refreshController.finish();
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
                    Runnable applyUi = () -> {
                        if (!isAdded()) return;
                        adapter.setItems(finalList);
                        adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                        emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                        animateRefreshComplete();
                    };

                    if (refreshController != null) {
                        refreshController.runAfterMinVisible(applyUi);
                    } else {
                        applyUi.run();
                    }
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
