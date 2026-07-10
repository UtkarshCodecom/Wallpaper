package com.walle.wallpaper.ui.wallpapers;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.facebook.shimmer.Shimmer;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.walle.wallpaper.R;

import androidx.core.content.ContextCompat;
import com.walle.wallpaper.data.WallpaperItem;
import com.walle.wallpaper.ui.common.ThemePickerSheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class RecentWallpaperAdapter extends RecyclerView.Adapter<RecentWallpaperAdapter.VH> {

    private final List<WallpaperItem> items = new ArrayList<>();
    private final Context ctx;
    private final int placeholderCount = 6; // number of placeholder items to show
    private int selectedPosition = RecyclerView.NO_POSITION;
    private SelectionListener selectionListener;
    // Loading mode: when true, adapter shows simple placeholder drawable instead of real items
    private boolean loading = false;
    private String selectedId;
    private boolean suppressSelectionCallback = false;
    private ItemClickListener itemClickListener;
    private Shimmer shimmerConfig;

    public RecentWallpaperAdapter(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the best image URL to show in the grid tile:
     * 1. First theme's previewUrl (if stored in the theme JSON)
     * 2. Wallpaper-level previewUrl
     * 3. Wallpaper-level bgUrl
     */
    private static String getDisplayUrl(WallpaperItem item) {
        if (item == null) return null;
        // Try first theme's previewUrl
        if (item.themes != null && !item.themes.isEmpty()) {
            LinkedHashMap<String, String> themeMap = ThemePickerSheet.buildThemeMap(item);
            if (!themeMap.isEmpty()) {
                String firstThemeJson = themeMap.entrySet().iterator().next().getValue();
                String themePreviewUrl = ThemePickerSheet.getThemePreviewUrl(firstThemeJson);
                if (themePreviewUrl != null && !themePreviewUrl.isEmpty()) return themePreviewUrl;
            }
        }
        // Fallback to wallpaper-level previewUrl / bgUrl
        if (item.previewUrl != null && !item.previewUrl.isEmpty()) return item.previewUrl;
        return item.bgUrl;
    }

    public void setSelectionListener(SelectionListener l) {
        this.selectionListener = l;
    }

    public void setItems(List<WallpaperItem> newItems) {
        loading = false;
        items.clear();
        if (newItems != null) items.addAll(newItems);
        selectedPosition = RecyclerView.NO_POSITION;

        // restore selection without triggering callback loop
        suppressSelectionCallback = true;
        try {
            setSelectedId(selectedId);
        } finally {
            suppressSelectionCallback = false;
        }

        notifyDataSetChanged();
    }

    public void setLoading(boolean isLoading) {
        this.loading = isLoading;
        notifyDataSetChanged();
    }

    private void dispatchUserSelectionChanged() {
        if (selectionListener == null) return;
        selectionListener.onSelectionChanged(selectedPosition,
                selectedPosition == RecyclerView.NO_POSITION ? null : items.get(selectedPosition));
    }

    public String getSelectedId() {
        return selectedId;
    }

    /**
     * Restore selection by wallpaper id (global selection).
     */
    public void setSelectedId(String id) {
        this.selectedId = id;
        if (loading) return;

        int newPos = RecyclerView.NO_POSITION;
        if (id != null && !id.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                WallpaperItem it = items.get(i);
                if (it != null && it.id != null && it.id.equals(id)) {
                    newPos = i;
                    break;
                }
            }
        }

        if (selectedPosition == newPos) return;

        int old = selectedPosition;
        selectedPosition = newPos;
        if (old != RecyclerView.NO_POSITION) notifyItemChanged(old);
        if (newPos != RecyclerView.NO_POSITION) notifyItemChanged(newPos);

        // Important: don't re-enter fragment selection code.
        if (!suppressSelectionCallback && selectionListener != null) {
            selectionListener.onSelectionChanged(selectedPosition,
                    selectedPosition == RecyclerView.NO_POSITION ? null : items.get(selectedPosition));
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Always inflate the normal preview item; when loading we'll show a placeholder drawable inside it
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_wallpaper_preview, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        if (loading) {
            // Pure skeleton tiles: shimmer over an empty gray box, no interactions.
            holder.selectionBar.setVisibility(View.GONE);
            if (holder.premiumStar != null) holder.premiumStar.setVisibility(View.GONE);
            if (holder.ytPlay != null) holder.ytPlay.setVisibility(View.GONE);
            if (holder.favButton != null) holder.favButton.setVisibility(View.GONE);
            Glide.with(ctx).clear(holder.image);
            holder.image.setImageDrawable(null);
            startShimmer(holder);
            holder.itemView.setOnClickListener(null);
            return;
        }

        // Use adapter position when possible to avoid stale 'position' usage
        int adapterPos = holder.getAdapterPosition();
        if (adapterPos == RecyclerView.NO_POSITION) adapterPos = position;

        WallpaperItem it = items.get(adapterPos);

        // If we have a persisted ID, force selection from it.
        if (selectedId != null && it != null && it.id != null && it.id.equals(selectedId)) {
            selectedPosition = adapterPos;
        }

        // Show or hide selection bar - TEMPORARILY DISABLED
        // holder.selectionBar.setVisibility(adapterPos == selectedPosition ? View.VISIBLE : View.GONE);
        holder.selectionBar.setVisibility(View.GONE);

        // Show or hide premium star based on isPremium flag
        if (holder.premiumStar != null) {
            holder.premiumStar.setVisibility(it != null && it.isPremium ? View.VISIBLE : View.GONE);
        }

        // YouTube play button — only when this wallpaper has a link.
        if (holder.ytPlay != null) {
            final String yt = (it != null) ? it.ytLink : null;
            boolean hasYt = yt != null && !yt.trim().isEmpty();
            holder.ytPlay.setVisibility(hasYt ? View.VISIBLE : View.GONE);
            holder.ytPlay.setOnClickListener(hasYt ? v -> openYouTube(ctx, yt) : null);
        }

        // Favorite heart (bottom-left).
        if (holder.favButton != null) {
            final String favId = (it != null) ? it.id : null;
            holder.favButton.setVisibility(favId != null ? View.VISIBLE : View.GONE);
            if (favId != null) {
                updateFavIcon(holder.favButton, com.walle.wallpaper.util.FavoritesStore.isFavorite(ctx, favId));
                holder.favButton.setOnClickListener(v ->
                        updateFavIcon(holder.favButton, com.walle.wallpaper.util.FavoritesStore.toggle(ctx, favId)));
            } else {
                holder.favButton.setOnClickListener(null);
            }
        }

        String url = getDisplayUrl(it);
        // Show a shimmering skeleton until the real image is decoded, then reveal it.
        startShimmer(holder);
        Glide.with(ctx)
                .load(url)
                .centerCrop()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@androidx.annotation.Nullable GlideException e, Object model,
                                                Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        stopShimmer(holder);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                   Target<android.graphics.drawable.Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        stopShimmer(holder);
                        return false;
                    }
                })
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            int newPos = holder.getAdapterPosition();
            if (newPos == RecyclerView.NO_POSITION) return;

            if (itemClickListener != null) {
                itemClickListener.onItemClick(items.get(newPos));
            }

            WallpaperItem clicked = items.get(newPos);
            selectedId = (clicked != null) ? clicked.id : null;

            int old = selectedPosition;
            if (selectedPosition == newPos) {
                selectedPosition = RecyclerView.NO_POSITION;
                selectedId = null;
            } else {
                selectedPosition = newPos;
            }
            notifyItemChanged(old);
            notifyItemChanged(selectedPosition);

            // User-driven selection only
            dispatchUserSelectionChanged();
        });
    }

    /**
     * A visible YouTube-style shimmer: solid gray base with a lighter band that sweeps
     * across. Built once and reused across tiles.
     */
    private Shimmer getShimmerConfig() {
        if (shimmerConfig == null) {
            shimmerConfig = new Shimmer.ColorHighlightBuilder()
                    .setBaseColor(ContextCompat.getColor(ctx, R.color.skeleton))
                    .setHighlightColor(ContextCompat.getColor(ctx, R.color.skeleton_highlight))
                    .setBaseAlpha(1f)
                    .setHighlightAlpha(1f)
                    .setDuration(1100)
                    .setAutoStart(true)
                    .build();
        }
        return shimmerConfig;
    }

    private void startShimmer(VH holder) {
        if (holder.shimmer == null) return;
        holder.shimmer.setShimmer(getShimmerConfig());
        holder.shimmer.setVisibility(View.VISIBLE);
        holder.shimmer.startShimmer();
    }

    private void stopShimmer(VH holder) {
        if (holder.shimmer == null) return;
        holder.shimmer.stopShimmer();
        holder.shimmer.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return loading ? placeholderCount : items.size();
    }

    public WallpaperItem getSelectedItem() {
        if (selectedPosition == RecyclerView.NO_POSITION) return null;
        return items.get(selectedPosition);
    }

    public void setItemClickListener(ItemClickListener l) {
        this.itemClickListener = l;
    }

    public interface SelectionListener {
        void onSelectionChanged(int position, WallpaperItem item);
    }

    public interface ItemClickListener {
        void onItemClick(@NonNull WallpaperItem item);
    }

    private void updateFavIcon(ImageView btn, boolean fav) {
        btn.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        btn.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx,
                fav ? R.color.accent : R.color.white));
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        View selectionBar;
        ImageView premiumStar;
        ImageView ytPlay;
        ImageView favButton;
        ShimmerFrameLayout shimmer;

        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.image_preview);
            selectionBar = v.findViewById(R.id.selection_bar);
            premiumStar = v.findViewById(R.id.premium_star);
            ytPlay = v.findViewById(R.id.yt_play_button);
            favButton = v.findViewById(R.id.fav_button);
            shimmer = v.findViewById(R.id.shimmer);
        }
    }

    /** Open a YouTube link, preferring the YouTube app and falling back to the browser. */
    static void openYouTube(Context ctx, String link) {
        if (ctx == null || link == null || link.trim().isEmpty()) return;
        android.net.Uri uri = android.net.Uri.parse(link.trim());
        android.content.Intent app = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .setPackage("com.google.android.youtube")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(app);
        } catch (Exception notInstalled) {
            try {
                ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }
}
