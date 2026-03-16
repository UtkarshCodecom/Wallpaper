package com.infinity.wallpaper.util;

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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
                // delete partial file before retry
                if (dest.exists()) dest.delete();
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
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
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    total += read;
                    if (listener != null) {
                        int p = (contentLength > 0) ? (int) ((total * 100) / contentLength) : -1;
                        listener.onProgress(total, contentLength, false);
                    }
                }
                fos.flush();
                if (listener != null) listener.onProgress(total, total, true);
            }

            // Validate the written file
            if (!dest.exists() || dest.length() == 0) {
                throw new Exception("Downloaded file is empty: " + dest.getAbsolutePath());
            }
        }
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long contentLength, boolean done);
    }
}
