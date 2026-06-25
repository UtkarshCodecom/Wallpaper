package com.walle.wallpaper;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.walle.wallpaper.util.DownloadWithProgress;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Simple wallpaper applier that:
 * 1. Downloads bg and mask to internal storage (with progress)
 * 2. Saves theme config to prefs
 * 3. Launches system wallpaper chooser for our service
 */
public class WallpaperApplier {

    private static final String TAG = "WallpaperApplier";
    private static final String PREFS = "wallpaper_prefs";

    private static String fingerprint(@NonNull String bgUrl, @Nullable String maskUrl, @Nullable Object themeObj) {
        String theme;
        try {
            if (themeObj instanceof String) theme = (String) themeObj;
            else if (themeObj != null) theme = new Gson().toJson(themeObj);
            else theme = "";
        } catch (Exception e) {
            theme = "";
        }
        return bgUrl + "|" + (maskUrl != null ? maskUrl : "") + "|" + theme.hashCode();
    }

    public static boolean hasPrefetched(@NonNull Context ctx,
                                        @NonNull String bgUrl,
                                        @Nullable String maskUrl,
                                        @Nullable Object themeObj) {
        try {
            String fp = fingerprint(bgUrl, maskUrl, themeObj);
            String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_fp", "");
            if (!fp.equals(stored)) return false;
            File dir = new File(ctx.getFilesDir(), "wallpaper");
            File bgFile = new File(dir, "bg.png");
            if (!bgFile.exists() || bgFile.length() <= 0) return false;
            if (maskUrl != null && !maskUrl.isEmpty()) {
                File maskFile = new File(dir, "mask.png");
                if (!maskFile.exists() || maskFile.length() <= 0) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Prefetches (downloads) bg + mask and stores theme_json. Does NOT open the system apply screen.
     * Use this on wallpaper tile click so downloads happen early and fast.
     */
    public static void prefetch(@NonNull Context ctx,
                                @NonNull String bgUrl,
                                @Nullable String maskUrl,
                                @Nullable Object themeObj,
                                @Nullable ProgressCallback progress,
                                @Nullable CompletionCallback done) {
        // If already downloaded, return immediately (no second loader)
        if (hasPrefetched(ctx, bgUrl, maskUrl, themeObj)) {
            if (done != null) done.onComplete(true, null);
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "prefetch() START bgUrl=" + bgUrl + " maskUrl=" + maskUrl);
                DownloadWithProgress dl = new DownloadWithProgress();

                File customBgFile = new File(ctx.getFilesDir(), "custom_bg.png");
                if (customBgFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    customBgFile.delete();
                }

                File dir = new File(ctx.getFilesDir(), "wallpaper");
                if (!dir.exists()) dir.mkdirs();

                File bgFile = new File(dir, "bg.png");
                File maskFile = new File(dir, "mask.png");
                boolean hasMask = maskUrl != null && !maskUrl.isEmpty();

                java.util.concurrent.atomic.AtomicReference<Exception> bgErr = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<Exception> maskErr = new java.util.concurrent.atomic.AtomicReference<>();

                Thread bgThread = new Thread(() -> {
                    try {
                        dl.download(bgUrl, bgFile, (bytesRead, contentLength, isDone) -> {
                            if (progress == null) return;
                            int p = (contentLength > 0) ? (int) ((bytesRead * 100) / contentLength) : -1;
                            progress.onProgress(Math.min(100, Math.max(-1, p)));
                        });
                    } catch (Exception e) {
                        bgErr.set(e);
                    }
                });

                Thread maskThread = new Thread(() -> {
                    try {
                        if (hasMask) {
                            dl.download(maskUrl, maskFile, null);
                        } else if (maskFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            maskFile.delete();
                        }
                    } catch (Exception e) {
                        maskErr.set(e);
                    }
                });

                bgThread.start();
                maskThread.start();
                bgThread.join();
                maskThread.join();

                if (bgErr.get() != null) throw bgErr.get();
                if (maskErr.get() != null) throw maskErr.get();

                Log.d(TAG, "BG+mask downloaded in parallel. bg size=" + bgFile.length());

                // ...rest unchanged (save prefs, broadcast, prefetchFonts, done callback)

                String themeJson = "";
                try {
                    if (themeObj instanceof String) themeJson = (String) themeObj;
                    else if (themeObj != null) themeJson = new Gson().toJson(themeObj);
                } catch (Exception ignored) {
                    themeJson = "";
                }

                String fp = fingerprint(bgUrl, maskUrl, themeObj);

                // Persist for service consumption
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("bg_url", bgUrl)
                        .putString("mask_url", maskUrl != null ? maskUrl : "")
                        .putString("theme_json", themeJson)
                        .putString("last_fp", fp)
                        .putLong("last_prefetch_ts", System.currentTimeMillis())
                        .putString("studio_overrides", "{}")
                        .apply();

                // Verify it was saved
                String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme_json", "");
                Log.d(TAG, "Theme JSON after save: " + saved);

                // notify service to reload
                try {
                    Intent notify = new Intent(com.walle.wallpaper.util.SettingsManager.ACTION_SETTINGS_CHANGED);
                    notify.setPackage(ctx.getPackageName());
                    ctx.sendBroadcast(notify);
                    Log.d(TAG, "Broadcast sent to service");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send broadcast: " + e.getMessage());
                }

                Log.d(TAG, "prefetch() DONE fp=" + fp);
                
                // Prefetch fonts used in the theme
                prefetchFonts(ctx, themeJson);

                if (done != null) done.onComplete(true, null);
            } catch (Exception e) {
                Log.e(TAG, "prefetch() FAILED: " + e.getMessage(), e);
                if (done != null) done.onComplete(false, e);
            }
        }).start();
    }

    /**
     * Opens the system live wallpaper apply screen for our service.
     */
    public static void openSystemApplyScreen(@NonNull Context ctx) {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(ctx, MyWallpaperServiceNew.class)
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    /**
     * Convenience: download assets + store theme, then open system apply screen.
     */
    public static void applyWithSystemChooser(@NonNull Context ctx,
                                              @NonNull String bgUrl,
                                              @Nullable String maskUrl,
                                              @Nullable Object themeObj,
                                              @Nullable ProgressCallback progress,
                                              @Nullable CompletionCallback done) {
        prefetch(ctx, bgUrl, maskUrl, themeObj, progress, (success, error) -> {
            try {
                openSystemApplyScreen(ctx);
            } catch (Exception e) {
                if (done != null) done.onComplete(false, e);
                return;
            }
            if (done != null) done.onComplete(success, error);
        });
    }

    /**
     * Auto-apply a static wallpaper without launching the system UI.
     * This is only used when the current wallpaper is already ours and user taps a tile.
     */
    public static void applyStaticIfPossible(@NonNull Context ctx,
                                             @NonNull File bgFile,
                                             @Nullable CompletionCallback done) {
        new Thread(() -> {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", bgFile);
                try (android.os.ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (pfd == null) throw new IllegalStateException("bg file not accessible");
                    wm.setStream(new java.io.FileInputStream(pfd.getFileDescriptor()));
                }
                if (done != null) done.onComplete(true, null);
            } catch (Exception e) {
                if (done != null) done.onComplete(false, e);
            }
        }).start();
    }

    /**
     * Best-effort check whether the current wallpaper belongs to this app.
     */
    public static boolean isOurLiveWallpaperActive(@NonNull Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            android.app.WallpaperInfo info = wm.getWallpaperInfo();
            if (info == null) return false;
            return ctx.getPackageName().equals(info.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void saveBitmap(Bitmap bmp, File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush();
        out.close();
    }

    private static void prefetchFonts(@NonNull Context ctx, @Nullable String themeJson) {
        if (themeJson == null || themeJson.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(themeJson);
            java.util.Set<String> fontsToDownload = new java.util.HashSet<>();

            JSONObject time = root.optJSONObject("time");
            if (time != null && time.has("font")) {
                String f = time.getString("font");
                if (f != null && !f.isEmpty()) fontsToDownload.add(f);
            }

            JSONObject date = root.optJSONObject("date");
            if (date != null && date.has("font")) {
                String f = date.getString("font");
                if (f != null && !f.isEmpty()) fontsToDownload.add(f);
            }

            if (fontsToDownload.isEmpty()) return;

            File fontDir = new File(ctx.getFilesDir(), "custom_fonts");
            for (String fontIdWithExt : fontsToDownload) {
                File cf = new File(fontDir, fontIdWithExt);
                if (!cf.exists()) {
                    // Need to find the URL from Firestore
                    String docId = fontIdWithExt;
                    if (docId.endsWith(".ttf")) docId = docId.substring(0, docId.length() - 4);
                    else if (docId.endsWith(".otf")) docId = docId.substring(0, docId.length() - 4);

                    final File finalFile = cf;
                    com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("fonts").document(docId).get()
                            .addOnSuccessListener(doc -> {
                                String url = doc.getString("url");
                                if (url != null && !url.isEmpty()) {
                                    new Thread(() -> {
                                        try {
                                            finalFile.getParentFile().mkdirs();
                                            new DownloadWithProgress().download(url, finalFile, null);
                                            Log.d(TAG, "Font prefetched: " + finalFile.getName());
                                            // Notify service again just in case it missed it
                                            Intent notify = new Intent(com.walle.wallpaper.util.SettingsManager.ACTION_SETTINGS_CHANGED);
                                            notify.setPackage(ctx.getPackageName());
                                            ctx.sendBroadcast(notify);
                                        } catch (Exception ignored) {
                                        }
                                    }).start();
                                }
                            });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "prefetchFonts failed: " + e.getMessage());
        }
    }

    public interface ProgressCallback {
        /**
         * progress 0..100, or -1 if unknown
         */
        void onProgress(int progress);
    }

    public interface CompletionCallback {
        void onComplete(boolean success, @Nullable Exception error);
    }
}