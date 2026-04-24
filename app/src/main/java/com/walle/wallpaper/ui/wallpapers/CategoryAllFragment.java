package com.walle.wallpaper.ui.wallpapers;

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
import com.walle.wallpaper.R;
import com.walle.wallpaper.WallpaperApplier;
import com.walle.wallpaper.data.WallpaperItem;
import com.walle.wallpaper.ui.AdminFragment;
import com.walle.wallpaper.ui.common.PullAwareSwipeRefreshLayout;
import com.walle.wallpaper.ui.common.ZigzagLoadingDialog;
import com.walle.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CategoryAllFragment extends Fragment {

    public static final String TOKEN_PREMIUM = "__PREMIUM__";
    public static final String TOKEN_RANDOM = "__RANDOM__";
    private static final String ARG_CATEGORY = "arg_category";
    private static final String ARG_FILTER = "arg_filter";
    private Dialog activeDialog = null;
    private View refreshIndicatorContainer;
    private com.walle.wallpaper.ui.common.PulseRefreshView pulseRefresh;
    private PullAwareSwipeRefreshLayout swipeRefresh;
    private com.walle.wallpaper.ui.common.PullRevealRefreshController refreshController;

    public static CategoryAllFragment newInstance(String category) {
        return newInstance(category, CategoryFilter.ALL);
    }

    public static CategoryAllFragment newInstance(String category, String filter) {
        CategoryAllFragment f = new CategoryAllFragment();
        Bundle b = new Bundle();
        b.putString(ARG_CATEGORY, category);
        b.putString(ARG_FILTER, filter);
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
        swipeRefresh = view.findViewById(R.id.swipe_refresh_category_all);
        refreshIndicatorContainer = view.findViewById(R.id.refresh_indicator_container);
        pulseRefresh = view.findViewById(R.id.pulse_refresh);

        if (swipeRefresh != null) {
            swipeRefresh.setProgressViewOffset(false, -200, -200);
            refreshController = new com.walle.wallpaper.ui.common.PullRevealRefreshController(swipeRefresh, refreshIndicatorContainer, pulseRefresh);
        }

        RecyclerView recycler = view.findViewById(R.id.recycler_all);
        // remove blue overscroll glow
        recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);

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

            com.walle.wallpaper.ui.common.ThemePickerSheet.show(requireContext(), item, (themeKey, themeJson, selectedItem) -> {
                if (!isAdded()) return;
                String bg = selectedItem.bgUrl != null && !selectedItem.bgUrl.isEmpty() ? selectedItem.bgUrl : selectedItem.previewUrl;
                String mask = selectedItem.maskUrl;
                if (bg == null || bg.isEmpty()) return;
                Object themeObj = (themeJson != null && !themeJson.equals("{}")) ? themeJson : getFirstTheme(selectedItem);

                if (activeDialog != null) return;
                activeDialog = ZigzagLoadingDialog.show(requireContext(), "Wallpaper applied successfully ✓");

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
                                com.walle.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), () -> {
                                    // When ad finishes, check if we need to open the screen
                                    if (WallpaperApplier.isOurLiveWallpaperActive(requireContext())) {
                                        java.io.File bgFile = new java.io.File(requireContext().getFilesDir(), "wallpaper/bg.png");
                                        WallpaperApplier.applyStaticIfPossible(requireContext(), bgFile, (ok, ex) -> {
                                        });
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

        // Persist selection on long press / selection change
        adapter.setSelectionListener((position, item) -> {
            SelectedWallpaperStore.setSelected(requireContext(), item);
            if (item != null) adapter.setSelectedId(item.id);
        });

        String cat = getArguments() != null ? getArguments().getString(ARG_CATEGORY) : null;
        String filter = getArguments() != null ? getArguments().getString(ARG_FILTER, CategoryFilter.ALL) : CategoryFilter.ALL;

        // Header/title behavior:
        // - In ViewAll tabs screen, the category title is shown above the tab bar, so hide header for ALL/FREE.
        // - Still hide header for PREMIUM token (category_title already says Premium).
        boolean shouldHideHeader = CategoryFilter.ALL.equals(filter) || CategoryFilter.FREE.equals(filter) || TOKEN_PREMIUM.equals(cat) || CategoryFilter.PREMIUM.equals(filter);
        if (shouldHideHeader) {
            header.setVisibility(View.GONE);
            // Remove top margin so cards start from top
            android.view.ViewGroup.MarginLayoutParams rlp = (android.view.ViewGroup.MarginLayoutParams) recycler.getLayoutParams();
            rlp.topMargin = 0;
            recycler.setLayoutParams(rlp);
        } else {
            // keep legacy header for any other uses
            int headerHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.15f);
            ViewGroup.LayoutParams lp = header.getLayoutParams();
            lp.height = headerHeight;
            header.setLayoutParams(lp);

            if (cat == null) headerTitle.setText(getString(R.string.all));
            else if (TOKEN_RANDOM.equals(cat)) headerTitle.setText(R.string.random);
            else headerTitle.setText(cat);

            recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    super.onScrolled(rv, dx, dy);
                    if (header.getVisibility() != View.VISIBLE) return;
                    int offset = rv.computeVerticalScrollOffset();
                    float t = Math.min(1f, offset / (float) headerHeight);
                    float scale = 1f - 0.4f * t;
                    header.setPivotX(0f);
                    header.setPivotY(0f);
                    header.setScaleX(scale);
                    header.setScaleY(scale);
                }
            });
        }

        if (swipeRefresh != null && refreshController != null) {
            refreshController.setOnRefresh(() -> loadWallpapers(adapter, emptyView));
        }

        loadWallpapers(adapter, emptyView);
    }

    private void dismissActiveDialog() {
        ZigzagLoadingDialog.dismiss(activeDialog);
        activeDialog = null;
    }

    private void showRefreshIndicator() {
        if (!isAdded()) return;
        if (refreshIndicatorContainer != null)
            refreshIndicatorContainer.setVisibility(View.VISIBLE);
        if (pulseRefresh != null) pulseRefresh.startAnimation();
    }

    private void hideRefreshIndicator() {
        if (!isAdded()) return;
        if (pulseRefresh != null) pulseRefresh.stopAnimation();
        if (refreshIndicatorContainer != null) refreshIndicatorContainer.setVisibility(View.GONE);
    }

    private void loadWallpapers(RecentWallpaperAdapter adapter, TextView emptyView) {
        adapter.setLoading(true);

        // If user pulled to refresh, controller will already be showing + animating.
        // For initial load, show pulse as a normal loading hint.
        if (refreshController == null) {
            showRefreshIndicator();
        }

        String cat = getArguments() != null ? getArguments().getString(ARG_CATEGORY) : null;
        String filter = getArguments() != null ? getArguments().getString(ARG_FILTER, CategoryFilter.ALL) : CategoryFilter.ALL;

        // Global Premium screen should always be premium-only.
        if (TOKEN_PREMIUM.equals(cat)) {
            filter = CategoryFilter.PREMIUM;
        }
        final String finalFilter = filter;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            if (!isAdded()) return;

            List<WallpaperItem> list = new ArrayList<>();
            if (task.isSuccessful() && task.getResult() != null) {
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    try {
                        WallpaperItem w = doc.toObject(WallpaperItem.class);
                        w.id = doc.getId();

                        // Special screens
                        if (TOKEN_RANDOM.equals(cat)) {
                            // random ignores category and filter
                            list.add(w);
                            continue;
                        }

                        boolean matchesCategory;
                        if (TOKEN_PREMIUM.equals(cat)) {
                            // legacy premium token page (not per-category)
                            matchesCategory = true;
                        } else {
                            matchesCategory = (cat != null && w.category != null && w.category.equalsIgnoreCase(cat));
                        }
                        if (!matchesCategory) continue;

                        // Apply tab filter
                        if (CategoryFilter.PREMIUM.equals(finalFilter)) {
                            if (w.isPremium) list.add(w);
                        } else if (CategoryFilter.FREE.equals(finalFilter)) {
                            if (!w.isPremium) list.add(w);
                        } else {
                            // ALL
                            list.add(w);
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
                    if (refreshController != null) refreshController.finish();
                    else hideRefreshIndicator();
                    adapter.setItems(finalList);
                    adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                    emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } else {
                requireActivity().runOnUiThread(() -> {
                    if (refreshController != null) refreshController.finish();
                    else hideRefreshIndicator();
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
