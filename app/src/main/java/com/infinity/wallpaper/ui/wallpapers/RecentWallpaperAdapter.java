package com.infinity.wallpaper.ui.wallpapers;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.ui.common.ThemePickerSheet;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RecentWallpaperAdapter extends RecyclerView.Adapter<RecentWallpaperAdapter.VH> {

    private final List<WallpaperItem> items = new ArrayList<>();
    private final Context ctx;
    private int selectedPosition = RecyclerView.NO_POSITION;
    private SelectionListener selectionListener;
    // Loading mode: when true, adapter shows simple placeholder drawable instead of real items
    private boolean loading = false;
    private final int placeholderCount = 6; // number of placeholder items to show
    private String selectedId;
    private boolean suppressSelectionCallback = false;

    public RecentWallpaperAdapter(Context ctx) {
        this.ctx = ctx;
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

    /** Restore selection by wallpaper id (global selection). */
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

    private void dispatchUserSelectionChanged() {
        if (selectionListener == null) return;
        selectionListener.onSelectionChanged(selectedPosition,
                selectedPosition == RecyclerView.NO_POSITION ? null : items.get(selectedPosition));
    }

    public String getSelectedId() {
        return selectedId;
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
            // show placeholder drawable and disable interactions
            holder.selectionBar.setVisibility(View.GONE);
            if (holder.premiumStar != null) holder.premiumStar.setVisibility(View.GONE);
            Glide.with(ctx).load(R.drawable.placeholder_skeleton).centerCrop().into(holder.image);
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

        String url = getDisplayUrl(it);
        // Ensure images are center-cropped to avoid stretching
        Glide.with(ctx).load(url).placeholder(R.drawable.ic_launcher_foreground).centerCrop().into(holder.image);

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

    @Override
    public int getItemCount() {
        return loading ? placeholderCount : items.size();
    }

    public WallpaperItem getSelectedItem() {
        if (selectedPosition == RecyclerView.NO_POSITION) return null;
        return items.get(selectedPosition);
    }

    public interface SelectionListener {
        void onSelectionChanged(int position, WallpaperItem item);
    }

    public interface ItemClickListener {
        void onItemClick(@NonNull WallpaperItem item);
    }

    private ItemClickListener itemClickListener;

    public void setItemClickListener(ItemClickListener l) {
        this.itemClickListener = l;
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

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        View selectionBar;
        ImageView premiumStar;

        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.image_preview);
            selectionBar = v.findViewById(R.id.selection_bar);
            premiumStar = v.findViewById(R.id.premium_star);
        }
    }
}
