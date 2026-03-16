package com.infinity.wallpaper.data.upload;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal R2 uploader.
 *
 * Note: this implementation expects a pre-signed PUT URL (recommended for production).
 * Cloudflare R2 is S3-compatible, but S3 SigV4 signing should NOT be done with long-lived secret
 * keys embedded in the app.
 */
public final class R2Uploader {

    private R2Uploader() {}

    private static final OkHttpClient http = new OkHttpClient();

    public interface ProgressCallback {
        void onProgress(long bytesWritten, long totalBytes);
    }

    /**
     * Uploads the content at {@code fileUri} to {@code presignedPutUrl}.
     *
     * @return true on 2xx.
     */
    public static boolean uploadPresignedPut(
            @NonNull Context ctx,
            @NonNull Uri fileUri,
            @NonNull String presignedPutUrl,
            @NonNull String contentType,
            ProgressCallback cb
    ) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        long total = querySize(cr, fileUri);

        MediaType mt = MediaType.parse(contentType);
        RequestBody body = new ProgressRequestBody(cr, fileUri, mt, total, cb);

        Request req = new Request.Builder()
                .url(presignedPutUrl)
                .put(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }

    private static long querySize(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static final class ProgressRequestBody extends RequestBody {
        private static final int BUF_SIZE = 16 * 1024;

        private final ContentResolver cr;
        private final Uri uri;
        private final MediaType mediaType;
        private final long contentLength;
        private final ProgressCallback cb;

        ProgressRequestBody(ContentResolver cr, Uri uri, MediaType mt, long len, ProgressCallback cb) {
            this.cr = cr;
            this.uri = uri;
            this.mediaType = mt;
            this.contentLength = len;
            this.cb = cb;
        }

        @Override public MediaType contentType() { return mediaType; }

        @Override public long contentLength() {
            return contentLength >= 0 ? contentLength : -1;
        }

        @Override
        public void writeTo(@NonNull okio.BufferedSink sink) throws IOException {
            byte[] buf = new byte[BUF_SIZE];
            long written = 0;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new IOException("Unable to open input stream");
                int r;
                while ((r = in.read(buf)) != -1) {
                    sink.write(buf, 0, r);
                    written += r;
                    if (cb != null) cb.onProgress(written, contentLength());
                }
            }
        }
    }
}
