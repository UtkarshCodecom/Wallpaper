package com.infinity.wallpaper.ui.common;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

public class AdManager {
    private static final String TAG = "AdManager";

    private static InterstitialAd mInterstitialAd;
    private static boolean isAdLoading = false;
    private static boolean initialized = false;

    public static void init(Context context) {
        if (!initialized) {
            MobileAds.initialize(context, initializationStatus -> {
                Log.d(TAG, "AdMob initialized.");
                initialized = true;
                loadInterstitialAd(context);
            });
        }
    }

    public static void loadInterstitialAd(Context context) {
        if (mInterstitialAd != null || isAdLoading) {
            return;
        }
        isAdLoading = true;
        AdRequest adRequest = new AdRequest.Builder().build();
        String adUnitId = context.getString(com.infinity.wallpaper.R.string.admob_interstitial_id);
        InterstitialAd.load(context, adUnitId, adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        Log.d(TAG, "Ad loaded.");
                        mInterstitialAd = ad;
                        isAdLoading = false;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        Log.e(TAG, "Ad failed to load: " + error.getMessage());
                        mInterstitialAd = null;
                        isAdLoading = false;
                    }
                });
    }

    public static void showInterstitial(Activity activity, Runnable onAdDismissed) {
        if (mInterstitialAd != null) {
            mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    Log.d(TAG, "Ad dismissed fullscreen content.");
                    mInterstitialAd = null;
                    if (onAdDismissed != null) {
                        onAdDismissed.run();
                    }
                    loadInterstitialAd(activity); // preload next
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError adError) {
                    Log.e(TAG, "Ad failed to show: " + adError.getMessage());
                    mInterstitialAd = null;
                    if (onAdDismissed != null) {
                        onAdDismissed.run();
                    }
                }
            });
            mInterstitialAd.show(activity);
        } else {
            Log.d(TAG, "The interstitial ad wasn't ready yet.");
            if (onAdDismissed != null) {
                onAdDismissed.run();
            }
            loadInterstitialAd(activity); // load for next time
        }
    }
}
