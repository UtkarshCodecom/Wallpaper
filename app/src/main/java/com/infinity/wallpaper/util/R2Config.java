package com.infinity.wallpaper.util;

/**
 * Cloudflare R2 (S3-compatible) configuration.
 *
 * IMPORTANT (production): don't ship real access keys in the APK.
 * For now this is a hook point; wire it to a secure backend / presigned URLs later.
 */
public final class R2Config {
    private R2Config() {}

    public static final String BUCKET = "wallpaper";

    /** S3 API endpoint for your R2 account (S3 compatible). */
    public static final String ENDPOINT = "https://3abdc6da80177d38e57f385014487886.r2.cloudflarestorage.com";

    /**
     * Public base url for reading objects.
     *
     * For public buckets via r2.dev, use the public dev URL (no bucket in path).
     * Example: https://pub-xxxx.r2.dev/wallpapers/racer/bg.jpg
     */
    public static final String PUBLIC_BASE_URL = "https://pub-013b4937608440afb7272601285c334e.r2.dev";
}
