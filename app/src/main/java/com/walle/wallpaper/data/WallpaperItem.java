package com.walle.wallpaper.data;

import java.util.Map;

@com.google.firebase.firestore.IgnoreExtraProperties
public class WallpaperItem {
    public String id;
    public String name;
    public String category;
    public String bgUrl;
    public String previewUrl;
    public String maskUrl;
    public boolean isPremium;
    public String ytLink; // optional YouTube Shorts link; shows a play button on the tile
    public Map<String, Object> themes;

    @com.google.firebase.firestore.Exclude
    public long createdAt; // epoch ms — newest first sort in Recent tab
    // Admin-controlled display order: 1 = show first, 2 = second, … 0/absent = unordered
    // (unordered wallpapers keep newest-first ordering, listed after the ordered ones).
    public int order;

    public WallpaperItem() {
    }

    /**
     * The single ordering rule used everywhere wallpapers are listed (admin list and the
     * user-facing grids), so the admin's preview of the order matches what users see:
     * explicitly ordered wallpapers first (1, 2, 3 …), then all unordered ones newest-first.
     */
    public static int compareForDisplay(WallpaperItem a, WallpaperItem b) {
        int oa = a.order > 0 ? a.order : Integer.MAX_VALUE;
        int ob = b.order > 0 ? b.order : Integer.MAX_VALUE;
        if (oa != ob) return Integer.compare(oa, ob);
        if (a.createdAt != b.createdAt) return Long.compare(b.createdAt, a.createdAt);
        String na = a.name != null ? a.name : (a.id != null ? a.id : "");
        String nb = b.name != null ? b.name : (b.id != null ? b.id : "");
        return na.compareToIgnoreCase(nb);
    }
}
