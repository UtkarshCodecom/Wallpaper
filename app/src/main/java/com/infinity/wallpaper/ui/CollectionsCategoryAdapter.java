package com.infinity.wallpaper.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.infinity.wallpaper.R;

import java.util.ArrayList;
import java.util.List;

public class CollectionsCategoryAdapter extends RecyclerView.Adapter<CollectionsCategoryAdapter.VH> {

    private final List<String> items = new ArrayList<>();
    private final Context ctx;
    private OnCategoryClickListener listener;

    public interface OnCategoryClickListener {
        void onCategoryClick(String category);
    }

    public void setOnCategoryClickListener(OnCategoryClickListener l) {
        this.listener = l;
    }

    public CollectionsCategoryAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setItems(List<String> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_category, parent, false);
        // set width to roughly 1/3 of screen so almost 3 categories visible
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int desired = Math.round(screenWidth * 0.33f);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            lp.width = desired;
            v.setLayoutParams(lp);
        }
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String name = items.get(position);
        holder.name.setText(name);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCategoryClick(name);
        });
        // reset scale in case view recycled
        holder.itemView.setScaleX(0.95f);
        holder.itemView.setScaleY(0.95f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name;
        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_category_name);
        }
    }
}
