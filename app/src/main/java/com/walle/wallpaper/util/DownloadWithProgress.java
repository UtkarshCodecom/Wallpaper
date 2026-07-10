package com.walle.wallpaper.util;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadWithProgress {

    private static final String TAG = "DownloadWithProgress";
    private static final int MAX_RETRIES = 3;

    // Shared singleton with generous timeouts
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    public void download(String url, File dest, ProgressListener listener) throws Exception {
        Log.d(TAG, "download() START url=" + url + " dest=" + dest.getAbsolutePath());

        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                doDownload(url, dest, listener);
                Log.d(TAG, "download() SUCCESS url=" + url + " size=" + dest.length());
                return; // success
            } catch (Exception e) {
                lastEx = e;
                Log.w(TAG, "download() attempt " + attempt + " FAILED: " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        throw new Exception("Download failed after " + MAX_RETRIES + " attempts: " + lastEx.getMessage(), lastEx);
    }

    private void doDownload(String url, File dest, ProgressListener listener) throws Exception {
        // Ensure parent directory exists
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new Exception("Cannot create directory: " + parent.getAbsolutePath());
            }
        }

        // Download into a temp file and rename into place only when complete. Writing
        // straight to the destination leaves a partial file behind if the process dies
        // mid-download — and a partial font/image at the final path both renders as
        // garbage AND blocks every "download if missing" check from ever re-fetching it.
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");

        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "WallpaperApp/1.0")
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            Log.d(TAG, "HTTP " + resp.code() + " for " + url);
            if (!resp.isSuccessful()) {
                throw new Exception("HTTP " + resp.code() + " " + resp.message() + " for " + url);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new Exception("Empty response body for " + url);
            }

            long contentLength = body.contentLength();
            Log.d(TAG, "Content-Length=" + contentLength + " for " + url);

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    total += read;
                    if (listener != null) {
                        listener.onProgress(total, contentLength, false);
                    }
                }
                fos.flush();
                fos.getFD().sync();
                if (listener != null) listener.onProgress(total, total, true);
            }

            // Validate the temp file before committing it
            if (!tmp.exists() || tmp.length() == 0) {
                throw new Exception("Downloaded file is empty: " + dest.getAbsolutePath());
            }
            if (contentLength > 0 && tmp.length() != contentLength) {
                throw new Exception("Truncated download (" + tmp.length() + "/" + contentLength
                        + " bytes) for " + url);
            }

            // Commit: replace dest atomically (same directory → same filesystem)
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                if (!tmp.renameTo(dest)) {
                    throw new Exception("Could not move download into place: " + dest.getAbsolutePath());
                }
            }
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long contentLength, boolean done);
    }
}
