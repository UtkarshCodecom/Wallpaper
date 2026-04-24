package com.walle.wallpaper.ui.common;

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
            Log.d(TAG, "Skipping loadInterstitialAd: mInterstitialAd=" + (mInterstitialAd != null) + " isAdLoading=" + isAdLoading);
            return;
        }
        isAdLoading = true;
        Log.d(TAG, "Requesting interstitial ad load...");
        AdRequest adRequest = new AdRequest.Builder().build();
        String adUnitId = context.getString(com.walle.wallpaper.R.string.admob_interstitial_id);
        Log.d(TAG, "AdUnitID: " + adUnitId);
        InterstitialAd.load(context, adUnitId, adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        Log.e(TAG, "AdMob STATUS: SUCCESS - Interstitial Ad loaded successfully.");
                        mInterstitialAd = ad;
                        isAdLoading = false;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        Log.e(TAG, "AdMob STATUS: FAILED to load. Error Code: " + error.getCode() + " Message: " + error.getMessage() + " Domain: " + error.getDomain());
                        mInterstitialAd = null;
                        isAdLoading = false;
                    }
                });
    }

    public static void showInterstitial(Activity activity, Runnable onAdDismissed) {
        Log.d(TAG, "Attempting to show interstitial...");
        if (mInterstitialAd != null) {
            mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    Log.e(TAG, "AdMob STATUS: Ad dismissed fullscreen content.");
                    mInterstitialAd = null;
                    if (onAdDismissed != null) {
                        onAdDismissed.run();
                    }
                    loadInterstitialAd(activity); // preload next
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError adError) {
                    Log.e(TAG, "AdMob STATUS: FAILED to show: " + adError.getMessage() + " Code: " + adError.getCode());
                    mInterstitialAd = null;
                    if (onAdDismissed != null) {
                        onAdDismissed.run();
                    }
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    Log.e(TAG, "AdMob STATUS: Ad showed fullscreen content.");
                    mInterstitialAd = null; // Important: nullify it once shown
                }
            });
            mInterstitialAd.show(activity);
        } else {
            Log.e(TAG, "AdMob STATUS: The interstitial ad wasn't ready yet or failed to load. Firing callback immediately.");
            if (onAdDismissed != null) {
                onAdDismissed.run();
            }
            loadInterstitialAd(activity); // load for next time
        }
    }
}
