package com.infinity.wallpaper.data.upload;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import com.infinity.wallpaper.util.R2Config;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Direct Cloudflare R2 uploader using S3-compatible Signature V4.
 *
 * WARNING: This requires an Access Key ID / Secret Access Key, which should NOT be shipped
 * in production apps. Use only for internal/admin builds, or replace with a presigned-url backend.
 */
public final class R2DirectUploader {

    private R2DirectUploader() {}

    private static final OkHttpClient http = new OkHttpClient();

    public interface ProgressCallback {
        void onProgress(long bytesWritten, long totalBytes);
    }

    public static String upload(
            @NonNull Context ctx,
            @NonNull Uri fileUri,
            @NonNull String objectKey,
            @NonNull String contentType,
            @NonNull String accessKeyId,
            @NonNull String secretAccessKey,
            ProgressCallback cb
    ) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        long total = querySize(cr, fileUri);

        String payloadSha256 = sha256HexOfUri(cr, fileUri);

        AwsV4Signer.SignedHeaders signed = AwsV4Signer.signPut(
                accessKeyId,
                secretAccessKey,
                /* region */ "auto",
                /* service */ "s3",
                R2Config.ENDPOINT,
                R2Config.BUCKET,
                objectKey,
                contentType,
                payloadSha256
        );

        String url = R2Config.ENDPOINT + "/" + R2Config.BUCKET + "/" + objectKey;

        RequestBody body = new ProgressRequestBody(cr, fileUri, MediaType.parse(contentType), total, cb);

        Request.Builder b = new Request.Builder().url(url).put(body);
        for (String hk : signed.headers.keySet()) {
            String hv = signed.headers.get(hk);
            if (hv != null) b.header(hk, hv);
        }

        try (Response resp = http.newCall(b.build()).execute()) {
            if (!resp.isSuccessful()) {
                String msg = resp.body() != null ? resp.body().string() : ("HTTP " + resp.code());
                throw new IOException("R2 upload failed: " + resp.code() + " " + msg);
            }
        }

        return R2Config.PUBLIC_BASE_URL + "/" + objectKey;
    }

    private static String sha256HexOfUri(ContentResolver cr, Uri uri) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[32 * 1024];
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new IOException("Unable to open input stream");
                int r;
                while ((r = in.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SHA-256 compute failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
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

        @Override public long contentLength() { return contentLength >= 0 ? contentLength : -1; }

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
