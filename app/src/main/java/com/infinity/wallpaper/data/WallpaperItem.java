package com.infinity.wallpaper.data;

import java.util.Map;

public class WallpaperItem {
    public String id;
    public String name;
    public String category;
    public String bgUrl;
    public String previewUrl;
    public String maskUrl;
    public boolean isPremium;
    public Map<String, Object> themes;
    public long createdAt; // epoch ms — newest first sort in Recent tab

    public WallpaperItem() {
    }
}
