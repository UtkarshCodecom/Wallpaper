package com.infinity.wallpaper.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Release stub: never load secrets in production builds.
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
        return null;
    }
}
