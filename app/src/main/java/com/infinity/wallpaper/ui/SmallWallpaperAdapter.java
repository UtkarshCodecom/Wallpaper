package com.infinity.wallpaper.ui;

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
import com.infinity.wallpaper.util.SelectedWallpaperStore;

import java.util.ArrayList;
import java.util.List;

public class SmallWallpaperAdapter extends RecyclerView.Adapter<SmallWallpaperAdapter.VH> {

    private final List<WallpaperItem> items = new ArrayList<>();
    private final Context ctx;
    private int itemWidth = 0;
    private int itemHeight = 0; // Fixed height for carousel
    private String selectedId;

    public SmallWallpaperAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public SmallWallpaperAdapter(Context ctx, int itemWidthPx) {
        this.ctx = ctx;
        this.itemWidth = itemWidthPx;
        this.itemHeight = Math.round(itemWidthPx * 16f / 9f);
    }

    /** Constructor with explicit width and height (for carousel with fixed height) */
    public SmallWallpaperAdapter(Context ctx, int itemWidthPx, int itemHeightPx) {
        this.ctx = ctx;
        this.itemWidth = itemWidthPx;
        this.itemHeight = itemHeightPx;
    }

    public void setItemWidth(int w) {
        this.itemWidth = w;
        if (itemHeight == 0) {
            this.itemHeight = Math.round(w * 16f / 9f);
        }
    }

    public void setItems(List<WallpaperItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelectedId(String id) {
        this.selectedId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_small_wallpaper, parent, false);
        // Set initial dimensions
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(itemWidth, itemHeight);
        }
        if (itemWidth > 0) {
            lp.width = itemWidth;
            lp.height = itemHeight > 0 ? itemHeight : Math.round(itemWidth * 16f / 9f);
        }
        v.setLayoutParams(lp);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        WallpaperItem it = items.get(position);
        String url = it.previewUrl != null && !it.previewUrl.isEmpty() ? it.previewUrl : it.bgUrl;
        Glide.with(ctx).load(url).centerCrop().into(holder.image);

        boolean isSel = selectedId != null && it != null && it.id != null && it.id.equals(selectedId);
        // TEMPORARILY DISABLED - selection bar
        // holder.selectionBar.setVisibility(isSel ? View.VISIBLE : View.GONE);
        holder.selectionBar.setVisibility(View.GONE);

        // Show premium star if wallpaper is premium
        if (holder.premiumStar != null) {
            holder.premiumStar.setVisibility(it != null && it.isPremium ? View.VISIBLE : View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (it == null) return;
            selectedId = it.id;
            SelectedWallpaperStore.setSelectedId(ctx, selectedId);
            notifyDataSetChanged();
        });

        // Reset transforms - carousel effect will apply proper values
        holder.itemView.setScaleX(1.0f);
        holder.itemView.setScaleY(1.0f);
        holder.itemView.setAlpha(1.0f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        View selectionBar;
        ImageView premiumStar;
        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.image_small);
            selectionBar = v.findViewById(R.id.selection_bar);
            premiumStar = v.findViewById(R.id.premium_star);
        }
    }
}
