package com.walle.wallpaper;

import android.app.Application;

import com.walle.wallpaper.ui.common.AdManager;

public class App extends Application {
    public static App context;

    public void onCreate() {
        super.onCreate();
        context = this;

        // Init AdMob
        AdManager.init(this);
    }
}