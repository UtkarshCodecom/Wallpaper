package com.infinity.wallpaper.data.upload;

import androidx.annotation.NonNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal AWS Signature V4 signer for S3-compatible PUT (Cloudflare R2).
 */
final class AwsV4Signer {

    private AwsV4Signer() {}

    static final class SignedHeaders {
        final Map<String, String> headers;
        final String iso8601;

        SignedHeaders(Map<String, String> headers, String iso8601) {
            this.headers = headers;
            this.iso8601 = iso8601;
        }
    }

    static SignedHeaders signPut(
            @NonNull String accessKeyId,
            @NonNull String secretAccessKey,
            @NonNull String region,
            @NonNull String service,
            @NonNull String endpointBase,
            @NonNull String bucket,
            @NonNull String objectKey,
            @NonNull String contentType,
            @NonNull String payloadSha256
    ) {
        try {
            String host = URI.create(endpointBase).getHost();

            String canonicalUri = canonicalizePath("/" + bucket + "/" + objectKey);

            String iso8601 = iso8601Now();
            String dateStamp = iso8601.substring(0, 8);

            // Only sign stable headers. OkHttp may add/adjust others.
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("host", host);
            headers.put("content-type", contentType);
            headers.put("x-amz-content-sha256", payloadSha256);
            headers.put("x-amz-date", iso8601);

            List<String> signedHeaderKeys = sortedLowercaseKeys(headers);
            String signedHeaders = joinWithSemicolon(signedHeaderKeys);
            String canonicalHeaders = canonicalHeaders(headers, signedHeaderKeys);

            String canonicalRequest = "PUT\n" + canonicalUri + "\n" +
                    "\n" +
                    canonicalHeaders + "\n" +
                    signedHeaders + "\n" +
                    payloadSha256;

            String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" +
                    iso8601 + "\n" +
                    credentialScope + "\n" +
                    sha256Hex(canonicalRequest);

            byte[] signingKey = getSignatureKey(secretAccessKey, dateStamp, region, service);
            String signature = hmacSha256Hex(signingKey, stringToSign);

            String auth = "AWS4-HMAC-SHA256 " +
                    "Credential=" + accessKeyId + "/" + credentialScope + ", " +
                    "SignedHeaders=" + signedHeaders + ", " +
                    "Signature=" + signature;

            // Output actual request headers (case-insensitive on wire)
            Map<String, String> out = new LinkedHashMap<>();
            out.put("Host", host);
            out.put("Content-Type", contentType);
            out.put("x-amz-content-sha256", payloadSha256);
            out.put("x-amz-date", iso8601);
            out.put("Authorization", auth);

            return new SignedHeaders(out, iso8601);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String canonicalHeaders(Map<String, String> headers, List<String> signedHeaderKeysLower) {
        StringBuilder sb = new StringBuilder();
        for (String kLower : signedHeaderKeysLower) {
            // find original value by case-insensitive key
            String v = null;
            for (String k : headers.keySet()) {
                if (kLower.equals(k.toLowerCase(Locale.US))) {
                    v = headers.get(k);
                    break;
                }
            }
            if (v == null) v = "";
            sb.append(kLower).append(':').append(normalizeSpaces(v)).append('\n');
        }
        return sb.toString();
    }

    private static String normalizeSpaces(String s) {
        // Trim and collapse internal whitespace per SigV4
        return s.trim().replaceAll("\\s+", " ");
    }

    private static List<String> sortedLowercaseKeys(Map<String, String> headers) {
        List<String> keys = new ArrayList<>();
        for (String k : headers.keySet()) keys.add(k.toLowerCase(Locale.US));
        Collections.sort(keys);
        return keys;
    }

    private static String joinWithSemicolon(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    private static String canonicalizePath(String path) {
        // Encode per AWS: unreserved = ALPHA / DIGIT / '-' / '.' / '_' / '~'
        // Keep '/' separators.
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                    c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                out.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append('%').append(String.format(Locale.US, "%02X", b));
                }
            }
        }
        return out.toString();
    }

    private static String iso8601Now() {
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(byte[] key, String data) throws Exception {
        return toHex(hmacSha256(key, data));
    }

    private static byte[] getSignatureKey(String secret, String date, String region, String service) throws Exception {
        byte[] kDate = hmacSha256(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static String sha256Hex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(data.getBytes(StandardCharsets.UTF_8));
        return toHex(dig);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
