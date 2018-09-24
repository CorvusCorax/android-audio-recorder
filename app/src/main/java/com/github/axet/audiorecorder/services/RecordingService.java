package com.github.axet.audiorecorder.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.view.ContextThemeWrapper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.github.axet.androidlibrary.widgets.ProximityShader;
import com.github.axet.androidlibrary.widgets.RemoteViewsCompat;
import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.audiolibrary.app.Storage;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;
import com.github.axet.audiorecorder.activities.RecordingActivity;
import com.github.axet.audiorecorder.app.MainApplication;

import java.io.File;

/**
 * RecordingActivity more likly to be removed from memory when paused then service. Notification button
 * does not handle getActvity without unlocking screen. The only option is to have Service.
 * <p/>
 * So, lets have it.
 * <p/>
 * Maybe later this class will be converted for fully feature recording service with recording thread.
 */
public class RecordingService extends Service {
    public static final String TAG = RecordingService.class.getSimpleName();

    public static final int NOTIFICATION_RECORDING_ICON = 1;

    public static String SHOW_ACTIVITY = RecordingService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String PAUSE_BUTTON = RecordingService.class.getCanonicalName() + ".PAUSE_BUTTON";
    public static String RECORD_BUTTON = RecordingService.class.getCanonicalName() + ".RECORD_BUTTON";

    Storage storage; // for storage path
    Notification notification;

    public static void startIfEnabled(Context context) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (!shared.getBoolean(MainApplication.PREFERENCE_CONTROLS, false))
            return;
        start(context);
    }

    public static void startIfPending(Context context) {
        Storage st = new Storage(context);
        if (st.recordingPending()) {
            final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
            String f = shared.getString(MainApplication.PREFERENCE_TARGET, "");
            String d;
            if (f.startsWith(ContentResolver.SCHEME_CONTENT)) {
                Uri u = Uri.parse(f);
                d = Storage.getDocumentName(u);
            } else if (f.startsWith(ContentResolver.SCHEME_FILE)) {
                Uri u = Uri.parse(f);
                File file = new File(u.getPath());
                d = file.getName();
            } else {
                File file = new File(f);
                d = file.getName();
            }
            startService(context, d, false, false);
            return;
        }
        startIfEnabled(context);
    }

    public static void start(Context context) {
        context.startService(new Intent(context, RecordingService.class));
    }

    public static void startService(Context context, String targetFile, boolean recording, boolean encoding) {
        context.startService(new Intent(context, RecordingService.class)
                .putExtra("targetFile", targetFile)
                .putExtra("recording", recording)
                .putExtra("encoding", encoding)
        );
    }

    public static void stopRecording(Context context) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (shared.getBoolean(MainApplication.PREFERENCE_CONTROLS, false)) {
            start(context);
            return;
        }
        stopService(context);
    }

    public static void stopService(Context context) {
        context.stopService(new Intent(context, RecordingService.class));
    }

    public RecordingService() {
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        storage = new Storage(this);

        showNotificationAlarm(true, new Intent());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        if (intent != null) {
            String a = intent.getAction();
            if (a == null) {
                showNotificationAlarm(true, intent);
            } else if (a.equals(PAUSE_BUTTON)) {
                Intent i = new Intent(RecordingActivity.PAUSE_BUTTON);
                sendBroadcast(i);
            } else if (a.equals(RECORD_BUTTON)) {
                RecordingActivity.startActivity(this, false);
            } else if (a.equals(SHOW_ACTIVITY)) {
                ProximityShader.closeSystemDialogs(this);
                if (intent.getStringExtra("targetFile") == null)
                    MainActivity.startActivity(this);
                else
                    RecordingActivity.startActivity(this, !intent.getBooleanExtra("recording", false));
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public class Binder extends android.os.Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");
        showNotificationAlarm(false, null);
    }

    @SuppressLint("RestrictedApi")
    public Notification build(Intent intent) {
        String targetFile = intent.getStringExtra("targetFile");
        boolean recording = intent.getBooleanExtra("recording", false);
        boolean encoding = intent.getBooleanExtra("encoding", false);

        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(SHOW_ACTIVITY).putExtra("targetFile", targetFile).putExtra("recording", recording),
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent pe = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(PAUSE_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent re = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(RECORD_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteViews view = new RemoteViews(getPackageName(), MainApplication.getTheme(this, R.layout.notifictaion_recording_light, R.layout.notifictaion_recording_dark));

        ContextThemeWrapper theme = new ContextThemeWrapper(this, MainApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark));
        RemoteViewsCompat.setImageViewTint(view, R.id.icon_circle, ThemeUtils.getThemeColor(theme, R.attr.colorButtonNormal)); // android:tint="?attr/colorButtonNormal" not working API16
        RemoteViewsCompat.applyTheme(theme, view);

        String title;
        String text;
        if (targetFile == null) {
            title = getString(R.string.app_name);
            Uri f = storage.getStoragePath();
            long free = storage.getFree(f);
            long sec = Storage.average(this, free);
            text = MainApplication.formatFree(this, free, sec);
            view.setViewVisibility(R.id.notification_record, View.VISIBLE);
            view.setOnClickPendingIntent(R.id.notification_record, re);
            view.setViewVisibility(R.id.notification_pause, View.GONE);
        } else {
            if (recording)
                title = getString(R.string.recording_title);
            else
                title = getString(R.string.pause_title);
            text = ".../" + targetFile;
            view.setViewVisibility(R.id.notification_record, View.GONE);
            view.setViewVisibility(R.id.notification_pause, View.VISIBLE);
        }

        if (encoding) {
            view.setViewVisibility(R.id.notification_pause, View.GONE);
            title = getString(R.string.encoding_title);
        }

        view.setOnClickPendingIntent(R.id.status_bar_latest_event_content, main);
        view.setTextViewText(R.id.notification_title, title);
        view.setTextViewText(R.id.notification_text, text);
        view.setOnClickPendingIntent(R.id.notification_pause, pe);
        view.setImageViewResource(R.id.notification_pause, !recording ? R.drawable.ic_play_arrow_black_24dp : R.drawable.ic_pause_black_24dp);
        RemoteViewsCompat.setContentDescription(view, R.id.notification_pause, getString(!recording ? R.string.record_button : R.string.pause_button));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setOngoing(true)
                .setContentTitle(title)
                .setContentText(text)
                .setTicker(title)
                .setWhen(notification == null ? System.currentTimeMillis() : notification.when)
                .setSmallIcon(R.drawable.ic_mic)
                .setContent(view);

        if (Build.VERSION.SDK_INT < 11) {
            builder.setContentIntent(main);
        }

        if (Build.VERSION.SDK_INT >= 21)
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        Notification n = builder.build();
        ((MainApplication) getApplication()).channelStatus.apply(n);
        return n;
    }

    public void showNotificationAlarm(boolean show, Intent intent) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!show) {
            stopForeground(false);
            nm.cancel(NOTIFICATION_RECORDING_ICON);
            notification = null;
        } else {
            Notification n = build(intent);
            if (notification == null)
                startForeground(NOTIFICATION_RECORDING_ICON, n);
            else
                nm.notify(NOTIFICATION_RECORDING_ICON, n);
            notification = n;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved");
    }
}
