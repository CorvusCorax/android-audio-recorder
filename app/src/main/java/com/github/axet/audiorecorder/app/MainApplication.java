package com.github.axet.audiorecorder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.preference.PreferenceManager;

import com.github.axet.androidlibrary.widgets.NotificationChannelCompat;
import com.github.axet.audiolibrary.encoders.FormatFLAC;
import com.github.axet.audiolibrary.encoders.FormatM4A;
import com.github.axet.audiolibrary.encoders.FormatOGG;
import com.github.axet.audiorecorder.R;

public class MainApplication extends com.github.axet.audiolibrary.app.MainApplication {

    public static final String PREFERENCE_CONTROLS = "controls";
    public static final String PREFERENCE_TARGET = "target";
    public static final String PREFERENCE_FLY = "fly";
    public static final String PREFERENCE_SOURCE = "bluetooth";

    public static final String PREFERENCE_VERSION = "version";

    public NotificationChannelCompat channelStatus;

    public int getUserTheme() {
        return getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        channelStatus = new NotificationChannelCompat(this, "status", "Status", NotificationManagerCompat.IMPORTANCE_LOW);
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
                    edit.putString(MainApplication.PREFERENCE_ENCODING, FormatM4A.EXT);
                else
                    edit.putString(MainApplication.PREFERENCE_ENCODING, FormatFLAC.EXT);
                edit.commit();
            }
            SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor edit = shared.edit();
            edit.putInt(PREFERENCE_VERSION, 1);
            edit.commit();
        } else { // second start, check version
            SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
            switch (shared.getInt(PREFERENCE_VERSION, 0)) {
                case 0:
                    version_0_to_1();
                    break;
            }
        }
        setTheme(getUserTheme());
    }

    void version_0_to_1() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 1);
        edit.putFloat(PREFERENCE_VOLUME, shared.getFloat(PREFERENCE_VOLUME, 0) + 1); // update volume from 0..1 to 0..1..4
        edit.commit();
    }

}
