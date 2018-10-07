package com.github.axet.audiorecorder.services;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;

// default bluetooth stack for API25 bugged and has to be cleared using this Receiver.
//
// some devices when you call startBluetoothSco twice, you have to call stopBluetoothSco twice, this helper class solve this issue
public class BluetoothReceiver extends BroadcastReceiver {

    public static int CONNECT_DELAY = 3000; // give os time ot initialize device, or startBluetoothSco will be ignored

    public Context context;
    public Handler handler = new Handler();
    public boolean bluetoothSource = false; // are we using bluetooth source recording
    public boolean bluetoothStart = false; // did we start already?
    public boolean pausedByBluetooth = false;
    public boolean errors = false; // show errors
    public boolean connecting = false;
    public IntentFilter filter = new IntentFilter();

    public Runnable connected = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(connected);
            if (pausedByBluetooth) {
                pausedByBluetooth = false;
                onConnected();
            }
        }
    };

    public Runnable disconnected = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(connected);
            onDisconnected();
            if (errors) {
                errors = false;
                Toast.makeText(context, R.string.hold_by_bluetooth, Toast.LENGTH_SHORT).show();
            }
            if (connecting) {
                connecting = false;
                stopBluetooth();
            }
        }
    };

    public BluetoothReceiver() {
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
    }

    public void registerReceiver(Context context) {
        this.context = context;
        context.registerReceiver(this, filter);
    }

    public void close() {
        context.unregisterReceiver(this);
    }

    public void onConnected() {
    }

    public void onDisconnected() {
        pausedByBluetooth = true;
        stopBluetooth();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null)
            return;
        if (bluetoothSource && a.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
            handler.postDelayed(connected, CONNECT_DELAY);
        }
        if (bluetoothSource && a.equals(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            switch (state) {
                case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                    connected.run();
                    break;
                case AudioManager.SCO_AUDIO_STATE_CONNECTING:
                    connecting = true;
                    break;
                case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                    disconnected.run();
                    break;
            }
        }
    }

    public void onStartBluetoothSco() {
    }

    @SuppressWarnings("deprecation")
    public boolean startBluetooth() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am.isBluetoothScoAvailableOffCall()) {
            if (!bluetoothStart) {
                if (Build.VERSION.SDK_INT == 21) {
                    if (!am.isWiredHeadsetOn()) // crash on lolipop devices: https://stackoverflow.com/questions/26642218
                        return false;
                }
                am.startBluetoothSco();
                bluetoothStart = true;
                onStartBluetoothSco();
            }
            if (!am.isBluetoothScoOn()) {
                pausedByBluetooth = true;
                return false;
            }
        }
        return true;
    }

    public void stopBluetooth() {
        handler.removeCallbacks(connected);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (bluetoothStart) {
            bluetoothStart = false;
            am.stopBluetoothSco();
        }
    }

    public boolean isRecordingReady() {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (shared.getString(AudioApplication.PREFERENCE_SOURCE, context.getString(R.string.source_mic)).equals(context.getString(R.string.source_bluetooth))) {
            bluetoothSource = true;
            if (!startBluetooth())
                return false;
        } else {
            bluetoothSource = false;
        }
        return true;
    }

}
