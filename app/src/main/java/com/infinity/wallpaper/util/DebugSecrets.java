package com.infinity.wallpaper.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.infinity.wallpaper.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Debug-only secrets loader.
 *
 * Reads secrets from a debug-only raw resource file: app/src/debug/res/raw/r2_keys.json
 * so keys are not committed into main sources.
 */
public final class DebugSecrets {

    private DebugSecrets() {}

    public static final class R2Keys {
        public final String accessKeyId;
        public final String secretAccessKey;

        public R2Keys(String accessKeyId, String secretAccessKey) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
        }
    }

    @Nullable
    public static R2Keys loadR2Keys(@NonNull Context ctx) {
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.r2_keys);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            String ak = o.optString("accessKeyId", "");
            String sk = o.optString("secretAccessKey", "");
            if (ak.trim().isEmpty()) return null;
            if (sk.trim().isEmpty()) return null;
            if ("CHANGE_ME".equalsIgnoreCase(ak.trim())) return null;
            if ("CHANGE_ME".equalsIgnoreCase(sk.trim())) return null;
            return new R2Keys(ak.trim(), sk.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
