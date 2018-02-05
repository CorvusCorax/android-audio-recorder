package com.github.axet.audiorecorder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.v7.preference.PreferenceManager;

import com.github.axet.audiolibrary.encoders.FormatOGG;
import com.github.axet.audiorecorder.R;

public class MainApplication extends com.github.axet.audiolibrary.app.MainApplication {

    public static final String PREFERENCE_CONTROLS = "controls";
    public static final String PREFERENCE_TARGET = "target";
    public static final String PREFERENCE_FLY = "fly";
    public static final String PREFERENCE_BLUETOOTH = "bluetooth";

    public int getUserTheme() {
        return getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final SharedPreferences defaultValueSp = getSharedPreferences(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, Context.MODE_PRIVATE);
        if (!defaultValueSp.getBoolean(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, false)) {
            PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
            if (!FormatOGG.supported(this)) {
                SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor edit = shared.edit();
                if (Build.VERSION.SDK_INT >= 18)
                    edit.putString(MainApplication.PREFERENCE_ENCODING, "m4a");
                else
                    edit.putString(MainApplication.PREFERENCE_ENCODING, "flac");
                edit.commit();
            }
        }
        setTheme(getUserTheme());
    }

}
