package com.github.axet.audiorecorder.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * http://stackoverflow.com/questions/2133986
 */
public class OnUpgradeReceiver extends BroadcastReceiver {
    String TAG = OnUpgradeReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive");
        RecordingService.startIfPending(context);
    }
}
