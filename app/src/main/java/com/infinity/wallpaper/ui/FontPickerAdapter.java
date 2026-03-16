package com.infinity.wallpaper.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.infinity.wallpaper.R;

import java.util.List;

public class FontPickerAdapter extends RecyclerView.Adapter<FontPickerAdapter.VH> {

    public interface OnFontSelected {
        void onSelected(String fontFile);
    }

    private final Context ctx;
    private final List<String> fonts; // file names like "main1.ttf"
    private String selectedFont;
    private OnFontSelected listener;

    public FontPickerAdapter(Context ctx, List<String> fonts, String selectedFont) {
        this.ctx = ctx;
        this.fonts = fonts;
        this.selectedFont = selectedFont;
    }

    public void setListener(OnFontSelected l) { this.listener = l; }

    public void setSelected(String font) {
        String old = selectedFont;
        selectedFont = font;
        int oldIdx = fonts.indexOf(old);
        int newIdx = fonts.indexOf(font);
        if (oldIdx >= 0) notifyItemChanged(oldIdx);
        if (newIdx >= 0) notifyItemChanged(newIdx);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_font_picker, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        String fontFile = fonts.get(pos);
        String name = fontFile.replace(".ttf", "").replace(".otf", "");

        try {
            Typeface tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/" + fontFile);
            h.sample.setTypeface(tf);
        } catch (Exception e) {
            h.sample.setTypeface(Typeface.DEFAULT_BOLD);
        }

        h.sample.setText("Aa");
        h.name.setText(name);

        boolean selected = fontFile.equals(selectedFont);
        h.itemView.setBackgroundResource(selected ? R.drawable.tab_active_indicator : R.drawable.tab_inactive_bg);
        h.sample.setTextColor(selected ? 0xFFFFD600 : 0xFFFFFFFF);

        h.itemView.setOnClickListener(v -> {
            setSelected(fontFile);
            if (listener != null) listener.onSelected(fontFile);
        });
    }

    @Override public int getItemCount() { return fonts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView sample, name;
        VH(View v) {
            super(v);
            sample = v.findViewById(R.id.tv_font_sample);
            name   = v.findViewById(R.id.tv_font_name);
        }
    }
}
