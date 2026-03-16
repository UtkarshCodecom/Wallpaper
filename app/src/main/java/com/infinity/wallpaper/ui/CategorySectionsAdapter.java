package com.infinity.wallpaper.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.infinity.wallpaper.R;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CategorySectionsAdapter extends RecyclerView.Adapter<CategorySectionsAdapter.VH> {

    public interface OnViewAllClickListener {
        void onViewAll(String category);
    }

    private final Context ctx;
    private final List<String> categories = new ArrayList<>();
    private final Map<String, List<WallpaperItem>> byCategory;
    private final OnViewAllClickListener viewAllListener;

    private final int fullItemWidth;
    private final int itemHeight;

    // ── Coverflow constants ──────────────────────────────────────────────────
    /** Max rotateY degrees applied to cards at the far edges */
    private static final float MAX_ROTATION_Y   = 42f;
    /** Center card scale (1.0 = no scale). Side cards shrink toward MIN_SCALE */
    private static final float CENTER_SCALE      = 1.00f;
    private static final float MIN_SCALE         = 0.82f;
    /** Alpha at center vs edge */
    private static final float CENTER_ALPHA      = 1.00f;
    private static final float EDGE_ALPHA        = 0.50f;
    /** Z elevation (dp): center card pops forward */
    private static final float CENTER_ELEVATION_DP = 12f;
    private static final float EDGE_ELEVATION_DP   = 0f;

    public CategorySectionsAdapter(Context ctx, Map<String, List<WallpaperItem>> byCategory) {
        this(ctx, byCategory, null);
    }

    public CategorySectionsAdapter(Context ctx, Map<String, List<WallpaperItem>> byCategory,
                                   OnViewAllClickListener listener) {
        this.ctx = ctx;
        this.byCategory = byCategory;
        this.viewAllListener = listener;
        this.categories.addAll(byCategory.keySet());

        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        // ~40% of screen — shows ~2 full cards and a peek on each side
        fullItemWidth = Math.round(screenWidth * 0.52f);
        // 9:16 portrait ratio
        itemHeight = Math.round(fullItemWidth * 16f / 9f);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_category_section, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String cat = categories.get(position);
        holder.title.setText(cat);
        List<WallpaperItem> list = byCategory.get(cat);
        if (list == null) list = new ArrayList<>();

        RecyclerView recycler = holder.recycler;

        holder.btnViewAll.setOnClickListener(v -> {
            if (viewAllListener != null) viewAllListener.onViewAll(cat);
        });

        String selectedId = SelectedWallpaperStore.getSelectedId(ctx);
        final int spacing   = dpToPx(12);
        final int sidePad   = dpToPx(20);   // padding so partial cards peek on both sides

        // ── Re-use path ──────────────────────────────────────────────────────
        Object tag = recycler.getTag();
        if ("coverflow_v3".equals(tag)) {
            RecyclerView.Adapter<?> a = recycler.getAdapter();
            if (a instanceof SmallWallpaperAdapter) {
                ((SmallWallpaperAdapter) a).setItems(list);
                ((SmallWallpaperAdapter) a).setSelectedId(selectedId);
            }
            recycler.post(() -> applyCoverflowEffect(recycler));
            return;
        }

        // ── First-time setup ─────────────────────────────────────────────────
        LinearLayoutManager lm = new LinearLayoutManager(
                ctx, LinearLayoutManager.HORIZONTAL, false);
        recycler.setLayoutManager(lm);
        recycler.setPadding(sidePad, dpToPx(10), sidePad, dpToPx(10));
        recycler.setClipToPadding(false);
        recycler.setClipChildren(false);
        // Allow cards to overdraw each other for the 3-D effect
        recycler.setItemAnimator(null);

        SmallWallpaperAdapter adapter = new SmallWallpaperAdapter(ctx, fullItemWidth, itemHeight);
        adapter.setItems(list);
        adapter.setSelectedId(selectedId);
        recycler.setAdapter(adapter);

        while (recycler.getItemDecorationCount() > 0) recycler.removeItemDecorationAt(0);
        recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View v,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State s) {
                out.right = spacing;
            }
        });

        try {
            if (recycler.getOnFlingListener() == null) {
                new LinearSnapHelper().attachToRecyclerView(recycler);
            }
        } catch (Exception ignored) {}

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                applyCoverflowEffect(rv);
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                applyCoverflowEffect(rv);
            }
        });

        recycler.setTag("coverflow_v3");
        recycler.post(() -> applyCoverflowEffect(recycler));
    }

    // ── Coverflow core ───────────────────────────────────────────────────────

    /**
     * Coverflow effect — fully API 21+ compatible:
     *  • CENTER card: scale 1.0, alpha 1.0, rotY 0°, max elevation, parallax 0
     *  • EDGE  cards: scale 0.82, alpha 0.5, rotY ±42°, zero elevation, parallax ±MAX
     *
     * Uses View.setRotationY + setCameraDistance for true perspective (no setAnimationMatrix).
     */
    private void applyCoverflowEffect(@NonNull RecyclerView rv) {
        if (rv.getWidth() == 0) return;

        float rvCenterX   = rv.getWidth() * 0.5f;
        float normalizeRange = fullItemWidth + dpToPx(12);   // one card + gap
        // Camera distance for perspective — multiply density so it looks same on all DPIs
        float cameraDist  = 8f * ctx.getResources().getDisplayMetrics().density * 1000f;

        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child == null) continue;

            float childCenterX = child.getLeft() + child.getWidth() * 0.5f;
            // –1 = one card left of center, 0 = center, +1 = one card right of center
            float rawOffset = (childCenterX - rvCenterX) / normalizeRange;
            float offset    = Math.max(-1.5f, Math.min(1.5f, rawOffset));
            float absOff    = Math.abs(offset);

            // Smooth easing: slow around center, faster at edges
            float t = easeInOutQuad(Math.min(1f, absOff));

            // 1. Perspective-correct rotateY — pivot at card center
            child.setPivotX(child.getWidth()  * 0.5f);
            child.setPivotY(child.getHeight() * 0.5f);
            child.setCameraDistance(cameraDist);
            child.setRotationY(-offset * MAX_ROTATION_Y);   // –right, +left

            // 2. Scale
            float scale = lerp(CENTER_SCALE, MIN_SCALE, t);
            child.setScaleX(scale);
            child.setScaleY(scale);

            // 3. Alpha
            child.setAlpha(lerp(CENTER_ALPHA, EDGE_ALPHA, t));

            // 4. Z elevation — center card floats forward above side cards
            child.setTranslationZ(
                    lerp(CENTER_ELEVATION_DP, EDGE_ELEVATION_DP, t)
                    * ctx.getResources().getDisplayMetrics().density);

            // 5. Vertical nudge — side cards drift slightly downward (adds depth)
            child.setTranslationY(t * dpToPx(8));

            // 6. Parallax: image inside card shifts laterally, revealing
            //    a different slice of the wallpaper for each position
            ImageView img = child.findViewById(R.id.image_small);
            if (img != null) {
                img.setTranslationX(-offset * dpToPx(18));
            }
        }
    }

    // ── Math helpers ─────────────────────────────────────────────────────────

    /** Smooth step: fast at edges, slow near center */
    private float easeInOutQuad(float t) {
        return t < 0.5f ? 2f * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) * 0.5f;
    }

    private float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private int dpToPx(int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    // ── Adapter boilerplate ──────────────────────────────────────────────────

    @Override public int getItemCount() { return categories.size(); }

    public static class VH extends RecyclerView.ViewHolder {
        TextView title, btnViewAll;
        RecyclerView recycler;

        VH(@NonNull View v) {
            super(v);
            title      = v.findViewById(R.id.section_title);
            recycler   = v.findViewById(R.id.horizontal_wallpapers);
            btnViewAll = v.findViewById(R.id.btn_view_all);
        }
    }
}




