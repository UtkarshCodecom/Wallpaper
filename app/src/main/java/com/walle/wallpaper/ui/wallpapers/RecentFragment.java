package com.walle.wallpaper.ui.wallpapers;

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

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.walle.wallpaper.R;
import com.walle.wallpaper.WallpaperApplier;
import com.walle.wallpaper.data.WallpaperItem;
import com.walle.wallpaper.ui.AdminFragment;
import com.walle.wallpaper.ui.common.PullAwareSwipeRefreshLayout;
import com.walle.wallpaper.ui.common.PullRevealRefreshController;
import com.walle.wallpaper.ui.common.ZigzagLoadingDialog;
import com.walle.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RecentFragment extends Fragment {

    private RecyclerView recycler;
    private RecentWallpaperAdapter adapter;
    private TextView emptyView;
    private PullAwareSwipeRefreshLayout swipeRefresh;
    private View refreshIndicatorContainer;
    private com.walle.wallpaper.ui.common.PulseRefreshView pulseRefresh;
    // guard against double-click spamming dialogs
    private Dialog activeDialog = null;
    private boolean isRefreshing = false;
    private PullRevealRefreshController refreshController;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.page_recent, container, false);
        recycler = root.findViewById(R.id.recycler_recent);
        emptyView = root.findViewById(R.id.empty_recent);
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        refreshIndicatorContainer = root.findViewById(R.id.refresh_indicator_container);
        pulseRefresh = root.findViewById(R.id.pulse_refresh);

        // Hide the default SwipeRefreshLayout indicator
        swipeRefresh.setProgressViewOffset(false, -200, -200);

        // Pull-to-reveal + release-to-animate behavior
        refreshController = new PullRevealRefreshController(swipeRefresh, refreshIndicatorContainer, pulseRefresh);
        refreshController.setOnRefresh(this::performRefresh);

        adapter = new RecentWallpaperAdapter(requireContext());
        adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));

        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));

        // ── Tap on a tile → choose theme or auto-apply when only one ──
        adapter.setItemClickListener(item -> {
            if (!isAdded()) return;
            SelectedWallpaperStore.setSelected(requireContext(), item);
            adapter.setSelectedId(item.id);

            int themeCount = getThemeCount(item);
            if (themeCount == 1) {
                // Directly apply the single available theme, no chooser bottom sheet
                String bg = item.bgUrl != null && !item.bgUrl.isEmpty() ? item.bgUrl : item.previewUrl;
                String mask = item.maskUrl;
                if (bg == null || bg.isEmpty()) return;

                Object themeObj = getSingleTheme(item);
                if (themeObj == null) {
                    themeObj = getFirstTheme(item);
                }

                if (activeDialog != null) return;
                activeDialog = ZigzagLoadingDialog.show(requireContext(), "Applying…  0%");

                Object finalThemeObj = themeObj;
                WallpaperApplier.prefetch(requireContext(), bg, mask, finalThemeObj,
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
                                        dismissActiveDialog();
                                        Toast.makeText(requireContext(), "Wallpaper applied successfully", Toast.LENGTH_SHORT).show();
                                    } else {
                                        dismissActiveDialog();
                                        WallpaperApplier.openSystemApplyScreen(requireContext());
                                    }
                                });
                                AdminFragment.incrementApplyCount(item.id);
                            });
                        });
                return;
            }

            // Multiple themes → show theme picker as before
            com.walle.wallpaper.ui.common.ThemePickerSheet.show(requireContext(), item, (themeKey, themeJson, selectedItem) -> {
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
                                com.walle.wallpaper.ui.common.AdManager.showInterstitial(requireActivity(), () -> {
                                    // When ad finishes, check if we need to open the screen
                                    if (WallpaperApplier.isOurLiveWallpaperActive(requireContext())) {
                                        dismissActiveDialog();
                                        Toast.makeText(requireContext(), "Wallpaper applied successfully ✓", Toast.LENGTH_SHORT).show();
                                    } else {
                                        dismissActiveDialog();
                                        WallpaperApplier.openSystemApplyScreen(requireContext());
                                    }
                                });
                                AdminFragment.incrementApplyCount(selectedItem.id);
                            });
                        });
            });

            // Keep selection listener for persisting selection
            adapter.setSelectionListener((position, selItem) -> {
                SelectedWallpaperStore.setSelected(requireContext(), selItem);
            });
        });

        loadWallpapers();
        return root;
    }

    private void dismissActiveDialog() {
        ZigzagLoadingDialog.dismiss(activeDialog);
        activeDialog = null;
    }

    private int getThemeCount(WallpaperItem item) {
        if (item == null || item.themes == null || item.themes.isEmpty()) return 0;
        int count = 0;
        for (Map.Entry<String, Object> e : item.themes.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof String) {
                String s = (String) v;
                if (!s.trim().isEmpty() && !"{}".equals(s.trim())) count++;
            } else if (v instanceof Map) {
                if (!((Map<?, ?>) v).isEmpty()) count++;
            } else {
                count++;
            }
        }
        return count;
    }

    private Object getSingleTheme(WallpaperItem item) {
        if (item == null || item.themes == null || item.themes.isEmpty()) return null;
        Object last = null;
        int count = 0;
        for (Map.Entry<String, Object> e : item.themes.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof String) {
                String s = (String) v;
                if (s.trim().isEmpty() || "{}".equals(s.trim())) continue;
            } else if (v instanceof Map) {
                if (((Map<?, ?>) v).isEmpty()) continue;
            }
            count++;
            last = v;
        }
        if (count == 1) {
            // Keep same representation type; ThemeRenderer/WallpaperApplier already handle String or Map
            return last;
        }
        return null;
    }

    private Object getFirstTheme(WallpaperItem item) {
        if (item.themes == null) return null;
        if (item.themes.containsKey("theme1")) return item.themes.get("theme1");
        for (Map.Entry<String, Object> e : item.themes.entrySet()) return e.getValue();
        return null;
    }

    private void performRefresh() {
        if (!isAdded() || isRefreshing) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            if (refreshController != null) refreshController.finish();
            return;
        }
        isRefreshing = true;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers")
                .get()
                .addOnCompleteListener(task -> {
            if (!isAdded()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                List<WallpaperItem> list = new ArrayList<>();
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    WallpaperItem w = doc.toObject(WallpaperItem.class);
                    w.id = doc.getId();

                    long cTime = 0;
                    Object cObj = doc.get("createdAt");
                    if (cObj instanceof com.google.firebase.Timestamp) {
                        cTime = ((com.google.firebase.Timestamp) cObj).toDate().getTime();
                    } else if (cObj instanceof Number) {
                        cTime = ((Number) cObj).longValue();
                    }

                    long uTime = 0;
                    Object uObj = doc.get("updatedAt");
                    if (uObj instanceof com.google.firebase.Timestamp) {
                        uTime = ((com.google.firebase.Timestamp) uObj).toDate().getTime();
                    } else if (uObj instanceof Number) {
                        uTime = ((Number) uObj).longValue();
                    }

                    w.createdAt = Math.max(cTime, uTime);
                    list.add(w);
                }
                Collections.sort(list, (a, b) -> {
                    if (a.createdAt != 0 || b.createdAt != 0)
                        return Long.compare(b.createdAt, a.createdAt);
                    String na = a.name != null ? a.name : a.id;
                    String nb = b.name != null ? b.name : b.id;
                    return nb.compareToIgnoreCase(na);
                });

                if (list.size() > 50) {
                    list = list.subList(0, 50);
                }

                final List<WallpaperItem> finalList = list;
                requireActivity().runOnUiThread(() -> {
                    if (refreshController != null) {
                        refreshController.runAfterMinVisible(() -> {
                            if (!isAdded()) return;
                            adapter.setItems(finalList);
                            adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                            emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                            animateRefreshComplete();
                        });
                    } else {
                        adapter.setItems(finalList);
                        adapter.setSelectedId(SelectedWallpaperStore.getSelectedId(requireContext()));
                        emptyView.setVisibility(finalList.isEmpty() ? View.VISIBLE : View.GONE);
                        animateRefreshComplete();
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

    private void animateRefreshComplete() {
        if (!isAdded()) return;

        if (refreshController != null) refreshController.finish();

        // Slide up + fade in animation
        recycler.setTranslationY(50f);
        recycler.setAlpha(0.3f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(recycler, "alpha", 0.3f, 1f);
        fadeIn.setDuration(350);
        fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator slideUp = ObjectAnimator.ofFloat(recycler, "translationY", 50f, 0f);
        slideUp.setDuration(400);
        slideUp.setInterpolator(new OvershootInterpolator(0.8f));

        fadeIn.start();
        slideUp.start();

        swipeRefresh.setRefreshing(false);
        isRefreshing = false;
    }

    private void loadWallpapers() {
        adapter.setLoading(true);
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers")
                .get()
                .addOnCompleteListener(task -> {
            if (!isAdded()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                List<WallpaperItem> list = new ArrayList<>();
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    WallpaperItem w = doc.toObject(WallpaperItem.class);
                    w.id = doc.getId();

                    long cTime = 0;
                    Object cObj = doc.get("createdAt");
                    if (cObj instanceof com.google.firebase.Timestamp) {
                        cTime = ((com.google.firebase.Timestamp) cObj).toDate().getTime();
                    } else if (cObj instanceof Number) {
                        cTime = ((Number) cObj).longValue();
                    }

                    long uTime = 0;
                    Object uObj = doc.get("updatedAt");
                    if (uObj instanceof com.google.firebase.Timestamp) {
                        uTime = ((com.google.firebase.Timestamp) uObj).toDate().getTime();
                    } else if (uObj instanceof Number) {
                        uTime = ((Number) uObj).longValue();
                    }

                    w.createdAt = Math.max(cTime, uTime);
                    list.add(w);
                }
                // Sort newest first: by createdAt desc, fallback alphabetical
                Collections.sort(list, (a, b) -> {
                    if (a.createdAt != 0 || b.createdAt != 0)
                        return Long.compare(b.createdAt, a.createdAt);
                    String na = a.name != null ? a.name : a.id;
                    String nb = b.name != null ? b.name : b.id;
                    return nb.compareToIgnoreCase(na);
                });

                if (list.size() > 50) {
                    list = list.subList(0, 50);
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
}
