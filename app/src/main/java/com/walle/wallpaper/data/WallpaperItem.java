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
    public Map<String, Object> themes;

    @com.google.firebase.firestore.Exclude
    public long createdAt; // epoch ms — newest first sort in Recent tab

    public WallpaperItem() {
    }
}
