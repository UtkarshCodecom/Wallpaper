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
        prefetch(ctx, bgUrl, maskUrl, themeObj, progress, done, false);
    }

    /**
     * @param deferCommit when true, the wallpaper is downloaded but NOT applied to the live
     *                    files/prefs. The download is stashed and the caller must call
     *                    {@link #commitPending(Context)} to actually apply it — used so the
     *                    apply only happens after an interstitial ad is fully watched. If the
     *                    ad is abandoned, the commit never runs and the current wallpaper is
     *                    left untouched.
     */
    public static void prefetch(@NonNull Context ctx,
                                @NonNull String bgUrl,
                                @Nullable String maskUrl,
                                @Nullable Object themeObj,
                                @Nullable ProgressCallback progress,
                                @Nullable CompletionCallback done,
                                boolean deferCommit) {
        // If already downloaded AND applied, return immediately (no second loader).
        if (hasPrefetched(ctx, bgUrl, maskUrl, themeObj)) {
            // Nothing to stage — this wallpaper is already the committed one.
            if (deferCommit) pendingApply = null;
            if (done != null) done.onComplete(true, null);
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "prefetch() START bgUrl=" + bgUrl + " maskUrl=" + maskUrl);
                DownloadWithProgress dl = new DownloadWithProgress();

                File dir = new File(ctx.getFilesDir(), "wallpaper");
                if (!dir.exists()) dir.mkdirs();

                File bgFile = new File(dir, "bg.png");
                File maskFile = new File(dir, "mask.png");
                // Download into temp files first so we never touch the currently-applied
                // wallpaper until the new one is fully downloaded. If the user cancels or a
                // download fails mid-way, the live bg.png/mask.png stay intact.
                File bgTmp = new File(dir, "bg.png.tmp");
                File maskTmp = new File(dir, "mask.png.tmp");
                boolean hasMask = maskUrl != null && !maskUrl.isEmpty();

                java.util.concurrent.atomic.AtomicReference<Exception> bgErr = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<Exception> maskErr = new java.util.concurrent.atomic.AtomicReference<>();

                Thread bgThread = new Thread(() -> {
                    try {
                        dl.download(bgUrl, bgTmp, (bytesRead, contentLength, isDone) -> {
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
                            dl.download(maskUrl, maskTmp, null);
                        }
                    } catch (Exception e) {
                        maskErr.set(e);
                    }
                });

                bgThread.start();
                maskThread.start();
                bgThread.join();
                maskThread.join();

                if (bgErr.get() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    bgTmp.delete();
                    //noinspection ResultOfMethodCallIgnored
                    maskTmp.delete();
                    throw bgErr.get();
                }
                if (maskErr.get() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    bgTmp.delete();
                    //noinspection ResultOfMethodCallIgnored
                    maskTmp.delete();
                    throw maskErr.get();
                }

                // Both downloads succeeded. Compute theme JSON + fingerprint.
                String themeJson = "";
                try {
                    if (themeObj instanceof String) themeJson = (String) themeObj;
                    else if (themeObj != null) themeJson = new Gson().toJson(themeObj);
                } catch (Exception ignored) {
                    themeJson = "";
                }
                String fp = fingerprint(bgUrl, maskUrl, themeObj);

                if (deferCommit) {
                    // Do NOT touch the live wallpaper yet. Stash the freshly-downloaded temp
                    // files so the caller can apply them AFTER an ad is fully watched. If the
                    // user abandons the ad, commit never runs and the applied wallpaper stays.
                    pendingApply = new PendingApply(bgTmp, maskTmp, bgFile, maskFile,
                            hasMask, bgUrl, maskUrl, themeJson, fp);
                    Log.d(TAG, "prefetch() DOWNLOADED (commit deferred) fp=" + fp);
                    if (done != null) done.onComplete(true, null);
                    return;
                }

                // Immediate commit into the live files (default behavior).
                if (!doCommit(ctx, bgTmp, maskTmp, bgFile, maskFile, hasMask, bgUrl, maskUrl, themeJson, fp)) {
                    throw new Exception("Failed to commit wallpaper files");
                }
                Log.d(TAG, "prefetch() DONE fp=" + fp);

                if (done != null) done.onComplete(true, null);
            } catch (Exception e) {
                Log.e(TAG, "prefetch() FAILED: " + e.getMessage(), e);
                if (done != null) done.onComplete(false, e);
            }
        }).start();
    }

    // ── Deferred apply (ad-gated) ────────────────────────────────────────────

    private static volatile PendingApply pendingApply = null;

    private static final class PendingApply {
        final File bgTmp, maskTmp, bgFile, maskFile;
        final boolean hasMask;
        final String bgUrl, maskUrl, themeJson, fp;

        PendingApply(File bgTmp, File maskTmp, File bgFile, File maskFile, boolean hasMask,
                     String bgUrl, String maskUrl, String themeJson, String fp) {
            this.bgTmp = bgTmp;
            this.maskTmp = maskTmp;
            this.bgFile = bgFile;
            this.maskFile = maskFile;
            this.hasMask = hasMask;
            this.bgUrl = bgUrl;
            this.maskUrl = maskUrl;
            this.themeJson = themeJson;
            this.fp = fp;
        }
    }

    /** True if a wallpaper has been downloaded with deferCommit and is awaiting commit. */
    public static boolean hasPendingApply() {
        return pendingApply != null;
    }

    /**
     * Apply the wallpaper most recently downloaded via {@code prefetch(..., deferCommit=true)}.
     * Call this only once the interstitial ad has been fully watched (or when no ad showed).
     * Returns true on success, or if there was nothing pending (already-applied wallpaper).
     */
    public static boolean commitPending(@NonNull Context ctx) {
        PendingApply p = pendingApply;
        pendingApply = null;
        if (p == null) return true; // nothing staged → already applied / no-op
        boolean ok = doCommit(ctx, p.bgTmp, p.maskTmp, p.bgFile, p.maskFile, p.hasMask,
                p.bgUrl, p.maskUrl, p.themeJson, p.fp);
        Log.d(TAG, "commitPending ok=" + ok + " fp=" + p.fp);
        return ok;
    }

    /** Discard a deferred download without applying it (used when the apply is abandoned). */
    public static void discardPending() {
        PendingApply p = pendingApply;
        pendingApply = null;
        if (p != null) {
            //noinspection ResultOfMethodCallIgnored
            p.bgTmp.delete();
            //noinspection ResultOfMethodCallIgnored
            p.maskTmp.delete();
            Log.d(TAG, "discardPending fp=" + p.fp);
        }
    }

    /** Atomically move the downloaded temp files into the live wallpaper + persist prefs. */
    private static boolean doCommit(Context ctx, File bgTmp, File maskTmp, File bgFile,
                                    File maskFile, boolean hasMask, String bgUrl,
                                    String maskUrl, String themeJson, String fp) {
        try {
            if (!atomicReplace(bgTmp, bgFile)) return false;
            if (hasMask) {
                if (!atomicReplace(maskTmp, maskFile)) return false;
            } else if (maskFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                maskFile.delete();
            }

            // The new wallpaper has its own bg now, so drop any custom uploaded bg.
            File customBgFile = new File(ctx.getFilesDir(), "custom_bg.png");
            if (customBgFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                customBgFile.delete();
            }

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

            // notify service to reload
            try {
                Intent notify = new Intent(com.walle.wallpaper.util.SettingsManager.ACTION_SETTINGS_CHANGED);
                notify.setPackage(ctx.getPackageName());
                ctx.sendBroadcast(notify);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send broadcast: " + e.getMessage());
            }

            // Prefetch fonts used in the theme
            prefetchFonts(ctx, themeJson);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "doCommit failed: " + e.getMessage(), e);
            return false;
        }
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

    /**
     * Atomically move {@code tmp} onto {@code dest}. On the same filesystem this replaces
     * the destination in a single rename so a reader never sees a half-written file.
     * Falls back to delete-then-rename if a plain rename is refused.
     */
    private static boolean atomicReplace(File tmp, File dest) {
        if (tmp == null || !tmp.exists() || tmp.length() == 0) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return true;
            }
        } catch (Exception ignored) {
            // fall through to File.renameTo below
        }
        if (tmp.renameTo(dest)) return true;
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        return tmp.renameTo(dest);
    }

    private static void saveBitmap(Bitmap bmp, File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush();
        out.close();
    }

    /**
     * The admin edit flow reuses the LIVE wallpaper's storage (theme_json pref +
     * files/wallpaper/bg.png) as its Studio workspace, which silently changes the
     * user's applied wallpaper. These two methods bracket an admin session: snapshot
     * the applied state before it gets stomped, restore it when the session ends.
     */
    public static void snapshotAppliedState(@NonNull Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("applied_backup_exists", false)) return; // keep the oldest (true) state
        String bgUrl = p.getString("bg_url", "");
        if (bgUrl == null || bgUrl.isEmpty()) return; // nothing applied yet — nothing to protect
        p.edit()
                .putBoolean("applied_backup_exists", true)
                .putString("applied_backup_bg_url", bgUrl)
                .putString("applied_backup_mask_url", p.getString("mask_url", ""))
                .putString("applied_backup_theme_json", p.getString("theme_json", ""))
                .putString("applied_backup_overrides", p.getString("studio_overrides", "{}"))
                .apply();
        Log.d(TAG, "snapshotAppliedState: saved applied wallpaper state before admin edit");
    }

    public static void restoreAppliedStateIfSnapshotted(@NonNull Context ctx) {
        final android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("applied_backup_exists", false)) return;
        final String bgUrl = p.getString("applied_backup_bg_url", "");
        final String maskUrl = p.getString("applied_backup_mask_url", "");
        final String themeJson = p.getString("applied_backup_theme_json", "");
        final String overrides = p.getString("applied_backup_overrides", "{}");
        // Clear the backup and blank last_fp: admin overwrote bg.png/mask.png WITHOUT
        // updating last_fp, so hasPrefetched() would wrongly claim the files are current
        // and skip the re-download.
        p.edit()
                .remove("applied_backup_exists")
                .remove("applied_backup_bg_url")
                .remove("applied_backup_mask_url")
                .remove("applied_backup_theme_json")
                .remove("applied_backup_overrides")
                .putString("last_fp", "")
                .apply();
        if (bgUrl == null || bgUrl.isEmpty()) return;
        Log.d(TAG, "restoreAppliedStateIfSnapshotted: restoring applied wallpaper after admin session");
        prefetch(ctx, bgUrl, (maskUrl == null || maskUrl.isEmpty()) ? null : maskUrl, themeJson,
                null, (ok, err) -> {
                    // prefetch resets studio_overrides to {} — put the user's tweaks back.
                    p.edit().putString("studio_overrides", overrides != null ? overrides : "{}").apply();
                    try {
                        Intent notify = new Intent(com.walle.wallpaper.util.SettingsManager.ACTION_SETTINGS_CHANGED);
                        notify.setPackage(ctx.getPackageName());
                        ctx.sendBroadcast(notify);
                    } catch (Exception ignored) {
                    }
                });
    }

    // Throttle for ensureThemeFontsAvailable — the existence checks are cheap but the
    // Firestore lookups behind a missing font shouldn't run on every redraw trigger.
    private static volatile long lastFontEnsureMs = 0;

    /**
     * Self-healing entry point for the live wallpaper service: re-downloads any font the
     * current (effective) theme needs that is missing from disk — e.g. because the original
     * download was interrupted and the partial file was later deleted as corrupt.
     */
    public static void ensureThemeFontsAvailable(@NonNull Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastFontEnsureMs < 60_000) return;
        lastFontEnsureMs = now;
        try {
            prefetchFonts(ctx, com.walle.wallpaper.util.StudioManager.getEffectiveThemeJson(ctx));
        } catch (Exception e) {
            Log.w(TAG, "ensureThemeFontsAvailable failed: " + e.getMessage());
        }
    }

    public static void prefetchFonts(@NonNull Context ctx, @Nullable String themeJson) {
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
                    final String fontName = fontIdWithExt;
                    com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("fonts").document(docId).get()
                            .addOnSuccessListener(doc -> {
                                String url = doc.getString("url");
                                if (url != null && !url.isEmpty()) {
                                    new Thread(() -> {
                                        try {
                                            finalFile.getParentFile().mkdirs();
                                            new DownloadWithProgress().download(url, finalFile, null);
                                            Log.d(TAG, "Font prefetched: " + finalFile.getName());
                                            // Drop any stale cached typeface (e.g. the fallback
                                            // loaded while the file was missing), then tell the
                                            // service to redraw with the real font.
                                            com.walle.wallpaper.render.ThemeRenderer.invalidateFontCache(fontName);
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