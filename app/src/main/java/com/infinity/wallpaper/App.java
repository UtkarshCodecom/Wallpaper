package com.infinity.wallpaper;

import android.app.Application;

public class App extends Application {
    public static App context;

    public void onCreate() {
        super.onCreate();
        context = this;
    }
}