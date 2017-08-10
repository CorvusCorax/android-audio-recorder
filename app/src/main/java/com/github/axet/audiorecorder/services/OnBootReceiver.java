package com.github.axet.audiorecorder.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class OnBootReceiver extends BroadcastReceiver {
    String TAG = OnBootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent i) {
        Log.d(TAG, "onReceive");
        RecordingService.startIfPending(context);
    }
}
