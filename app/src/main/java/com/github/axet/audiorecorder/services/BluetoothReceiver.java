package com.github.axet.audiorecorder.services;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.RecordingActivity;
import com.github.axet.audiorecorder.app.MainApplication;

// default bluetooth stack for API25 bugged and has to be cleared using this Receiver.
public class BluetoothReceiver extends BroadcastReceiver {

    public static int CONNECT_DELAY = 3000; // give os time ot initialize device, or startBluetoothSco will be ignored

    public Context context;
    public Handler handler = new Handler();
    public boolean bluetoothSource = false; // are we using bluetooth source recording
    public boolean bluetoothStart = false; // did we start already?
    public boolean pausedByBluetooth = false;
    public boolean errors = false; // show errors
    public boolean connecting = false;

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

    public BluetoothReceiver(Context context) {
        this.context = context;
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

    public boolean startBluetooth() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am.isBluetoothScoAvailableOffCall()) {
            if (!bluetoothStart) {
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
        if (shared.getString(MainApplication.PREFERENCE_BLUETOOTH, MainApplication.SOURCE_MIC).equals(MainApplication.SOURCE_BLUETOOTH)) {
            bluetoothSource = true;
            if (!startBluetooth())
                return false;
        } else {
            bluetoothSource = false;
        }
        return true;
    }

}
