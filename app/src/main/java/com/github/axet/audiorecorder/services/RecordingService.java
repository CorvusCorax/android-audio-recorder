package com.github.axet.audiorecorder.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.github.axet.androidlibrary.widgets.OptimizationPreferenceCompat;
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

    Storage storage = new Storage(this); // for storage path

    public static void startIfEnabled(Context context) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (!shared.getBoolean(MainApplication.PREFERENCE_CONTROLS, false))
            return;
        start(context);
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
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        startForeground(NOTIFICATION_RECORDING_ICON, build(new Intent()));
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
                if (!intent.getBooleanExtra("recording", false))
                    MainActivity.startActivity(this);
                else
                    RecordingActivity.startActivity(this, false);
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

        stopForeground(false);

        showNotificationAlarm(false, null);
    }

    public Notification build(Intent intent) {
        String targetFile = intent.getStringExtra("targetFile");
        boolean recording = intent.getBooleanExtra("recording", false);
        boolean encoding = intent.getBooleanExtra("encoding", false);

        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(SHOW_ACTIVITY).putExtra("recording", targetFile != null),
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent pe = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(PAUSE_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent re = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(RECORD_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteViews view = new RemoteViews(getPackageName(), MainApplication.getTheme(getBaseContext(),
                R.layout.notifictaion_recording_light,
                R.layout.notifictaion_recording_dark));

        String title;
        String text;
        if (targetFile == null) {
            title = getString(R.string.app_name);
            File f = storage.getStoragePath();
            long free = Storage.getFree(f);
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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setOngoing(true)
                .setContentTitle(title)
                .setContentText(text)
                .setTicker(title)
                .setSmallIcon(R.drawable.ic_mic_24dp)
                .setContent(view);

        if (Build.VERSION.SDK_INT < 11) {
            builder.setContentIntent(main);
        }

        if (Build.VERSION.SDK_INT >= 21)
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        return builder.build();
    }

    // alarm dismiss button
    public void showNotificationAlarm(boolean show, Intent intent) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!show) {
            notificationManager.cancel(NOTIFICATION_RECORDING_ICON);
        } else {
            notificationManager.notify(NOTIFICATION_RECORDING_ICON, build(intent));
        }
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved");
    }
}

