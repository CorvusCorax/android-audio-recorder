package com.github.axet.audiorecorder.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.view.ContextThemeWrapper;
import android.view.View;
import android.widget.RemoteViews;

import com.github.axet.androidlibrary.widgets.NotificationChannelCompat;
import com.github.axet.androidlibrary.widgets.RemoteViewsCompat;
import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.audiolibrary.encoders.FormatFLAC;
import com.github.axet.audiolibrary.encoders.FormatM4A;
import com.github.axet.audiolibrary.encoders.FormatOGG;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;

import java.util.Locale;

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
        switch (getVersion(PREFERENCE_VERSION, R.xml.pref_general)) {
            case -1:
                SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor edit = shared.edit();
                if (!FormatOGG.supported(this)) {
                    if (Build.VERSION.SDK_INT >= 18)
                        edit.putString(MainApplication.PREFERENCE_ENCODING, FormatM4A.EXT);
                    else
                        edit.putString(MainApplication.PREFERENCE_ENCODING, FormatFLAC.EXT);
                }
                edit.putInt(PREFERENCE_VERSION, 2);
                edit.commit();
                break;
            case 0:
                version_0_to_1();
                version_1_to_2();
                break;
            case 1:
                version_1_to_2();
                break;
        }
        setTheme(getUserTheme());
    }

    void version_0_to_1() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putFloat(PREFERENCE_VOLUME, shared.getFloat(PREFERENCE_VOLUME, 0) + 1); // update volume from 0..1 to 0..1..4
        edit.putInt(PREFERENCE_VERSION, 1);
        edit.commit();
    }

    @SuppressLint("RestrictedApi")
    void version_1_to_2() {
        Locale locale = Locale.getDefault();
        if (locale.toString().startsWith("ru")) {
            String title = "Программа переименована";
            String text = "'Аудио Рекордер' -> '" + getString(R.string.app_name) + "'";
            PendingIntent main = PendingIntent.getService(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteViews view = new RemoteViews(getPackageName(), MainApplication.getTheme(this, R.layout.notifictaion_recording_light, R.layout.notifictaion_recording_dark));
            ContextThemeWrapper theme = new ContextThemeWrapper(this, MainApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark));
            RemoteViewsCompat.setImageViewTint(view, R.id.icon_circle, ThemeUtils.getThemeColor(theme, R.attr.colorButtonNormal)); // android:tint="?attr/colorButtonNormal" not working API16
            RemoteViewsCompat.applyTheme(theme, view);
            view.setViewVisibility(R.id.notification_record, View.GONE);
            view.setViewVisibility(R.id.notification_pause, View.GONE);
            view.setOnClickPendingIntent(R.id.status_bar_latest_event_content, main);
            view.setTextViewText(R.id.notification_title, title);
            view.setTextViewText(R.id.notification_text, text);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setTicker(title)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContent(view);

            if (Build.VERSION.SDK_INT < 11)
                builder.setContentIntent(main);

            if (Build.VERSION.SDK_INT >= 21)
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            Notification n = builder.build();
            channelStatus.apply(n);
            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            nm.notify((int) System.currentTimeMillis(), n);
        }
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 2);
        edit.commit();
    }
}
