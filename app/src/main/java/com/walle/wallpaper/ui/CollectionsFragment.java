package com.walle.wallpaper.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.walle.wallpaper.R;
import com.walle.wallpaper.data.WallpaperItem;
import com.walle.wallpaper.ui.common.PullAwareSwipeRefreshLayout;
import com.walle.wallpaper.ui.common.PullRevealRefreshController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CollectionsFragment extends Fragment {

    private final List<String> categoryOrder = new ArrayList<>();
    private RecyclerView recyclerSections;
    private PullAwareSwipeRefreshLayout swipeRefresh;
    private View refreshIndicatorContainer;
    private com.walle.wallpaper.ui.common.PulseRefreshView pulseRefresh;
    private PullRevealRefreshController refreshController;
    private boolean isRefreshing = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_collections, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recyclerSections = view.findViewById(R.id.recycler_sections);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_collections);
        refreshIndicatorContainer = view.findViewById(R.id.refresh_indicator_container);
        pulseRefresh = view.findViewById(R.id.pulse_refresh);

        // Hide the default SwipeRefreshLayout indicator - we use our custom one
        swipeRefresh.setProgressViewOffset(false, -500, -500);

        refreshController = new PullRevealRefreshController(swipeRefresh, refreshIndicatorContainer, pulseRefresh);
        refreshController.setOnRefresh(this::refreshWithZigzag);

        loadCategories();
        loadAllWallpapersGrouped();
    }

    private void refreshWithZigzag() {
        if (isRefreshing) {
            swipeRefresh.setRefreshing(false);
            if (refreshController != null) refreshController.finish();
            return;
        }
        isRefreshing = true;

        // Clear and reload categories + wallpapers
        categoryOrder.clear();
        loadCategories();
        loadAllWallpapersGroupedRefresh();
    }

    private void animateRefreshComplete() {
        if (!isAdded()) return;
        if (refreshController != null) refreshController.finish();
        swipeRefresh.setRefreshing(false);
        isRefreshing = false;
    }

    private void loadAllWallpapersGroupedRefresh() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            if (!isAdded()) return;

            final Map<String, WallpaperItem> dedupe = new LinkedHashMap<>();
            if (task.isSuccessful() && task.getResult() != null) {
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    try {
                        WallpaperItem direct = doc.toObject(WallpaperItem.class);
                        // doc.toObject may still throw; guard for safety
                        if (direct != null) {
                            direct.id = doc.getId();
                            String key = (direct.id != null && !direct.id.isEmpty()) ? direct.id : doc.getId();
                            dedupe.put(key, direct);
                        }
                    } catch (Exception e) {
                        Log.w("CollectionsFragment", "Failed to parse wallpaper doc=" + doc.getId(), e);
                    }
                }
            }

            final List<WallpaperItem> resultList = new ArrayList<>(dedupe.values());

            Runnable applyUi = () -> {
                if (!isAdded()) return;
                buildAndShowSections(resultList);
                animateRefreshComplete();
            };

            if (refreshController != null) {
                refreshController.runAfterMinVisible(applyUi);
            } else {
                requireActivity().runOnUiThread(applyUi);
            }
        });
    }

    private void loadCategories() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        List<String> list = new ArrayList<>();
        db.collection("collection").get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    Object o = doc.get("name");
                    if (o != null) list.add(o.toString());
                    else list.add(doc.getId());
                }
                Log.d("CollectionsFragment", "Loaded categories (collection): count=" + list.size());
                categoryOrder.clear();
                categoryOrder.addAll(list);
            } else {
                db.collection("collections").get().addOnCompleteListener(task2 -> {
                    if (task2.isSuccessful() && task2.getResult() != null) {
                        for (QueryDocumentSnapshot doc : task2.getResult()) {
                            Object o = doc.get("name");
                            if (o != null) list.add(o.toString());
                            else list.add(doc.getId());
                        }
                    }
                    Log.d("CollectionsFragment", "Loaded categories (collections fallback): count=" + list.size());
                    categoryOrder.clear();
                    categoryOrder.addAll(list);
                });
            }
        });
    }

    private void loadAllWallpapersGrouped() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("wallpapers").get().addOnCompleteListener(task -> {
            final java.util.Map<String, WallpaperItem> dedupe = new LinkedHashMap<>();
            if (task.isSuccessful() && task.getResult() != null) {
                for (QueryDocumentSnapshot doc : task.getResult()) {
                    try {
                        WallpaperItem direct = doc.toObject(WallpaperItem.class);
                        if (direct != null) {
                            direct.id = doc.getId();
                            dedupe.put(direct.id != null ? direct.id : doc.getId(), direct);
                        }
                    } catch (Exception e) {
                    }

                    try {
                        for (String key : doc.getData().keySet()) {
                            Object value = doc.get(key);
                            if (value instanceof java.util.Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> m = (java.util.Map<String, Object>) value;
                                WallpaperItem it = mapToWallpaperItem(m);
                                if (it != null) {
                                    String mapKey = (it.id != null && !it.id.isEmpty()) ? it.id : (doc.getId() + ":" + key);
                                    it.id = mapKey;
                                    dedupe.put(mapKey, it);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    db.collection("wallpapers").document(doc.getId()).collection("names").get().addOnCompleteListener(sub -> {
                        if (sub.isSuccessful() && sub.getResult() != null) {
                            for (QueryDocumentSnapshot sd : sub.getResult()) {
                                try {
                                    WallpaperItem it = sd.toObject(WallpaperItem.class);
                                    if (it != null) {
                                        it.id = sd.getId();
                                        dedupe.put(it.id, it);
                                    } else {
                                        java.util.Map<String, Object> m = sd.getData();
                                        WallpaperItem it2 = mapToWallpaperItem(m);
                                        if (it2 != null) {
                                            it2.id = sd.getId();
                                            dedupe.put(it2.id, it2);
                                        }
                                    }
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                        // after each subcollection returns, rebuild sections to include newly fetched items
                        buildAndShowSections(new ArrayList<>(dedupe.values()));
                    });
                }
                // initial build with what we have synchronously
                buildAndShowSections(new ArrayList<>(dedupe.values()));
            } else {
                buildAndShowSections(new ArrayList<>());
            }
        });
    }

    private void buildAndShowSections(List<WallpaperItem> wallpapers) {
        // group by category (case-preserve first seen)
        Map<String, List<WallpaperItem>> temp = new LinkedHashMap<>();
        for (WallpaperItem it : wallpapers) {
            if (it == null) continue;
            // skip items without a category
            if (it.category == null || it.category.trim().isEmpty()) continue;
            String cat = it.category;

            String matchedKey = null;
            for (String k : temp.keySet()) {
                if (k.equalsIgnoreCase(cat)) {
                    matchedKey = k;
                    break;
                }
            }
            String key = matchedKey != null ? matchedKey : cat;
            List<WallpaperItem> bucket = temp.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                temp.put(key, bucket);
            }
            bucket.add(it);
        }

        // Build final ordered map honoring categoryOrder
        Map<String, List<WallpaperItem>> byCategory = new LinkedHashMap<>();
        // first add categories in categoryOrder if they exist in temp
        for (String ordered : categoryOrder) {
            for (String key : new ArrayList<>(temp.keySet())) {
                if (key.equalsIgnoreCase(ordered)) {
                    byCategory.put(key, temp.remove(key));
                    break;
                }
            }
        }
        // then add remaining categories
        byCategory.putAll(temp);

        // set up sections adapter on the UI thread
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            CategorySectionsAdapter sectionsAdapter = new CategorySectionsAdapter(requireContext(), byCategory, category -> {
                com.walle.wallpaper.ui.wallpapers.CategoryViewAllTabsFragment frag = com.walle.wallpaper.ui.wallpapers.CategoryViewAllTabsFragment.newInstance(category);
                requireActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.nav_host_fragment, frag)
                        .addToBackStack(null)
                        .commit();
            });
            recyclerSections.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));

            // Apply slide-up layout animation
            android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
            set.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            android.view.animation.TranslateAnimation slide = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.3f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.0f
            );
            slide.setDuration(400);
            android.view.animation.AlphaAnimation alpha = new android.view.animation.AlphaAnimation(0.0f, 1.0f);
            alpha.setDuration(400);
            set.addAnimation(slide);
            set.addAnimation(alpha);
            android.view.animation.LayoutAnimationController controller = new android.view.animation.LayoutAnimationController(set, 0.15f);
            recyclerSections.setLayoutAnimation(controller);

            recyclerSections.setAdapter(sectionsAdapter);
        });
    }

    private WallpaperItem mapToWallpaperItem(java.util.Map<String, Object> m) {
        if (m == null) return null;
        WallpaperItem it = new WallpaperItem();
        try {
            Object name = m.get("name");
            if (name != null) it.name = name.toString();
            Object cat = m.get("category");
            if (cat instanceof java.util.List) {
                java.util.List l = (java.util.List) cat;
                if (!l.isEmpty()) it.category = l.get(0).toString();
            } else if (cat != null) it.category = cat.toString();
            Object bg = m.get("bgUrl");
            if (bg != null) it.bgUrl = bg.toString();
            Object prev = m.get("previewUrl");
            if (prev != null) it.previewUrl = prev.toString();
            Object mask = m.get("maskUrl");
            if (mask != null) it.maskUrl = mask.toString();
            Object premium = m.get("isPremium");
            if (premium instanceof Boolean) it.isPremium = (Boolean) premium;
            else if (premium != null) it.isPremium = Boolean.parseBoolean(premium.toString());
            Object themes = m.get("themes");
            if (themes instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> tm = (java.util.Map<String, Object>) themes;
                it.themes = tm;
            }
            Object idv = m.get("id");
            if (idv != null) it.id = idv.toString();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return it;
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private int dpToPx(int dp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(dp * dm.density);
    }
}
