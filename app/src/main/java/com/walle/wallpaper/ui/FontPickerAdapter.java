package com.walle.wallpaper.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.walle.wallpaper.R;

import java.io.File;
import java.util.List;

public class FontPickerAdapter extends RecyclerView.Adapter<FontPickerAdapter.VH> {

    private final Context ctx;
    private final List<FontItem> fonts;
    private final File customFontDir;
    private String selectedFontId;
    private OnFontSelected listener;
    public FontPickerAdapter(Context ctx, List<FontItem> fonts, String selectedFontId) {
        this.ctx = ctx;
        this.fonts = fonts;
        this.selectedFontId = selectedFontId;
        this.customFontDir = new File(ctx.getFilesDir(), "custom_fonts");
    }

    public void setListener(OnFontSelected l) {
        this.listener = l;
    }

    public void setSelected(String fontId) {
        String old = selectedFontId;
        selectedFontId = fontId;
        int oldIdx = -1, newIdx = -1;
        for (int i = 0; i < fonts.size(); i++) {
            if (fonts.get(i).id.equals(old)) oldIdx = i;
            if (fonts.get(i).id.equals(fontId)) newIdx = i;
        }
        if (oldIdx >= 0) notifyItemChanged(oldIdx);
        if (newIdx >= 0) notifyItemChanged(newIdx);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_font_picker, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        FontItem fi = fonts.get(pos);

        try {
            Typeface tf;
            if (fi.isCustom) {
                File cf = new File(customFontDir, fi.id);
                if (cf.exists()) {
                    tf = Typeface.createFromFile(cf);
                } else {
                    tf = Typeface.DEFAULT_BOLD;
                }
            } else {
                tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/" + fi.id);
            }
            if (tf != null) {
                h.sample.setTypeface(tf);
            }
        } catch (Exception e) {
            h.sample.setTypeface(Typeface.DEFAULT_BOLD);
        }

        h.sample.setText("Aa");
        h.name.setText(fi.displayName);

        boolean selected = fi.id.equals(selectedFontId);
        h.itemView.setBackgroundResource(selected ? R.drawable.tab_active_indicator : R.drawable.tab_inactive_bg);
        h.sample.setTextColor(selected ? 0xFFFFD600 : 0xFFFFFFFF);

        h.itemView.setOnClickListener(v -> {
            setSelected(fi.id);
            if (listener != null) listener.onSelected(fi);
        });
    }

    @Override
    public int getItemCount() {
        return fonts.size();
    }

    public interface OnFontSelected {
        void onSelected(FontItem fontItem);
    }

    public static class FontItem {
        public final String id;
        public final String displayName;
        public final boolean isCustom;

        public FontItem(String id, String displayName, boolean isCustom) {
            this.id = id;
            this.displayName = displayName;
            this.isCustom = isCustom;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView sample, name;

        VH(View v) {
            super(v);
            sample = v.findViewById(R.id.tv_font_sample);
            name = v.findViewById(R.id.tv_font_name);
        }
    }
}
