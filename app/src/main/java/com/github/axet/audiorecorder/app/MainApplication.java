package com.github.axet.audiorecorder.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.preference.PreferenceManager;

import com.github.axet.androidlibrary.app.MainLibrary;
import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.audiorecorder.R;

public class MainApplication extends com.github.axet.audiolibrary.app.MainApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
    }

}
