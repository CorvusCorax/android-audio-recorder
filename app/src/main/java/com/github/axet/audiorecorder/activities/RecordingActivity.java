package com.github.axet.audiorecorder.activities;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.WindowCallbackWrapper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.axet.androidlibrary.animations.MarginBottomAnimation;
import com.github.axet.androidlibrary.sound.AudioTrack;
import com.github.axet.androidlibrary.widgets.AppCompatThemeActivity;
import com.github.axet.androidlibrary.widgets.ErrorDialog;
import com.github.axet.androidlibrary.widgets.OpenFileDialog;
import com.github.axet.androidlibrary.widgets.PopupWindowCompat;
import com.github.axet.androidlibrary.widgets.Toast;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.Factory;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.encoders.FormatWAV;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiolibrary.filters.AmplifierFilter;
import com.github.axet.audiolibrary.filters.SkipSilenceFilter;
import com.github.axet.audiolibrary.filters.VoiceFilter;
import com.github.axet.audiolibrary.widgets.PitchView;
import com.github.axet.audiorecorder.BuildConfig;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Storage;
import com.github.axet.audiorecorder.services.BluetoothReceiver;
import com.github.axet.audiorecorder.services.RecordingService;

import java.io.File;
import java.nio.ShortBuffer;

public class RecordingActivity extends AppCompatThemeActivity {
    public static final String TAG = RecordingActivity.class.getSimpleName();

    public static final int RESULT_START = 1;

    public static final String[] PERMISSIONS_AUDIO = new String[]{
            Manifest.permission.RECORD_AUDIO
    };

    public static final String ERROR = RecordingActivity.class.getCanonicalName() + ".ERROR";
    public static final String START_PAUSE = RecordingActivity.class.getCanonicalName() + ".START_PAUSE";
    public static final String PAUSE_BUTTON = RecordingActivity.class.getCanonicalName() + ".PAUSE_BUTTON";
    public static final String ACTION_FINISH_RECORDING = BuildConfig.APPLICATION_ID + ".STOP_RECORDING";

    public static String START_RECORDING = RecordingService.class.getCanonicalName() + ".START_RECORDING";
    public static String STOP_RECORDING = RecordingService.class.getCanonicalName() + ".STOP_RECORDING";

    PhoneStateChangeListener pscl = new PhoneStateChangeListener();
    FileEncoder encoder;
    MediaSessionCompat msc;

    boolean start = true; // do we need to start recording immidiatly?

    long editSample = -1; // current cut position in mono samples, stereo = editSample * 2

    AudioTrack play; // current play sound track

    TextView title;
    TextView time;
    String duration;
    TextView state;
    ImageButton pause;
    View done;
    PitchView pitch;

    ScreenReceiver screen;

    AudioApplication.RecordingStorage recording;

    RecordingReceiver receiver;

    AlertDialog muted;
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == AudioApplication.RecordingStorage.PINCH)
                pitch.add((Double) msg.obj);
            if (msg.what == AudioApplication.RecordingStorage.UPDATESAMPLES)
                updateSamples((Long) msg.obj);
            if (msg.what == AudioApplication.RecordingStorage.PAUSED) {
                muted = RecordingActivity.startActivity(RecordingActivity.this, "Error", getString(R.string.mic_paused));
                if (muted != null) {
                    AutoClose ac = new AutoClose(muted, 10);
                    ac.run();
                }
            }
            if (msg.what == AudioApplication.RecordingStorage.MUTED) {
                if (Build.VERSION.SDK_INT >= 28)
                    muted = RecordingActivity.startActivity(RecordingActivity.this, getString(R.string.mic_muted_error), getString(R.string.mic_muted_pie));
                else
                    muted = RecordingActivity.startActivity(RecordingActivity.this, "Error", getString(R.string.mic_muted_error));
            }
            if (msg.what == AudioApplication.RecordingStorage.UNMUTED) {
                if (muted != null) {
                    AutoClose run = new AutoClose(muted);
                    run.run();
                    muted = null;
                }
            }
            if (msg.what == AudioApplication.RecordingStorage.END) {
                pitch.drawEnd();
                if (!recording.interrupt.get()) {
                    stopRecording(getString(R.string.recording_status_pause));
                    String text = "Error reading from stream";
                    if (Build.VERSION.SDK_INT >= 28)
                        muted = RecordingActivity.startActivity(RecordingActivity.this, text, getString(R.string.mic_muted_pie));
                    else
                        muted = RecordingActivity.startActivity(RecordingActivity.this, getString(R.string.mic_muted_error), text);
                }
            }
            if (msg.what == AudioApplication.RecordingStorage.ERROR)
                Error((Exception) msg.obj);
        }
    };

    public static void startActivity(Context context, boolean pause) {
        Log.d(TAG, "startActivity");
        Intent i = new Intent(context, RecordingActivity.class);
        if (pause)
            i.setAction(RecordingActivity.START_PAUSE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(i);
    }

    public static AlertDialog startActivity(final Activity a, final String title, final String msg) {
        Log.d(TAG, "startActivity");
        Runnable run = new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(a, RecordingActivity.class);
                i.setAction(RecordingActivity.ERROR);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("error", title);
                i.putExtra("msg", msg);
                a.startActivity(i);
            }
        };
        if (a.isFinishing()) {
            run.run();
            return null;
        }
        try {
            AlertDialog muted = new ErrorDialog(a, msg).setTitle(title).show();
            Intent i = new Intent(a, RecordingActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            a.startActivity(i);
            return muted;
        } catch (Exception e) {
            Log.d(TAG, "startActivity", e);
            run.run();
            return null;
        }
    }

    public static void stopRecording(Context context) {
        context.sendBroadcast(new Intent(ACTION_FINISH_RECORDING));
    }

    public class AutoClose implements Runnable {
        int count = 5;
        AlertDialog d;
        Button button;

        public AutoClose(AlertDialog muted, int count) {
            this(muted);
            this.count = count;
        }

        public AutoClose(AlertDialog muted) {
            d = muted;
            button = d.getButton(DialogInterface.BUTTON_NEUTRAL);
            Window w = d.getWindow();
            touchListener(w);
        }

        public void touchListener(final Window w) {
            final Window.Callback c = w.getCallback();
            w.setCallback(new WindowCallbackWrapper(c) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    onUserInteraction();
                    return c.dispatchKeyEvent(event);
                }

                @Override
                public boolean dispatchTouchEvent(MotionEvent event) {
                    Rect rect = PopupWindowCompat.getOnScreenRect(w.getDecorView());
                    if (rect.contains((int) event.getRawX(), (int) event.getRawY()))
                        onUserInteraction();
                    return c.dispatchTouchEvent(event);
                }
            });
        }

        public void onUserInteraction() {
            Button b = d.getButton(DialogInterface.BUTTON_NEUTRAL);
            b.setVisibility(View.GONE);
            handler.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (isFinishing())
                return;
            if (!d.isShowing())
                return;
            if (count <= 0) {
                d.dismiss();
                return;
            }
            button.setText(d.getContext().getString(R.string.auto_close, count));
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                }
            });
            count--;
            handler.postDelayed(this, 1000);
        }
    }

    class RecordingReceiver extends BluetoothReceiver {
        @Override
        public void onConnected() {
            if (recording.thread == null) {
                if (isRecordingReady())
                    startRecording();
            }
        }

        @Override
        public void onDisconnected() {
            if (recording.thread != null) {
                stopRecording(getString(R.string.hold_by_bluetooth));
                super.onDisconnected();
            }
        }

        @Override
        public void onReceive(final Context context, Intent intent) {
            super.onReceive(context, intent);
            String a = intent.getAction();
            if (a == null)
                return;
            if (a.equals(PAUSE_BUTTON)) {
                pauseButton();
                return;
            }
            if (a.equals(ACTION_FINISH_RECORDING)) {
                done.performClick();
                return;
            }
            MediaButtonReceiver.handleIntent(msc, intent);
        }
    }

    class PhoneStateChangeListener extends PhoneStateListener {
        public boolean wasRinging;
        public boolean pausedByCall;

        @Override
        public void onCallStateChanged(int s, String incomingNumber) {
            switch (s) {
                case TelephonyManager.CALL_STATE_RINGING:
                    wasRinging = true;
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    wasRinging = true;
                    if (recording.thread != null) {
                        stopRecording(getString(R.string.hold_by_call));
                        pausedByCall = true;
                    }
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    if (pausedByCall) {
                        if (receiver.isRecordingReady())
                            startRecording();
                    }
                    wasRinging = false;
                    pausedByCall = false;
                    break;
            }
        }
    }

    public String toMessage(Throwable e) {
        Throwable t;
        if (encoder == null) {
            t = e;
        } else {
            t = encoder.getException();
            if (t == null)
                t = e;
        }
        return ErrorDialog.toMessage(t);
    }

    public void Error(Throwable e) {
        Log.e(TAG, "error", e);
        Error(toMessage(e));
    }

    public void Error(String msg) {
        ErrorDialog builder = new ErrorDialog(this, msg);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                finish();
            }
        });
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        final File in = recording.storage.getTempRecording();
        if (in.length() > 0) {
            builder.setNeutralButton(R.string.save_as_wav, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final OpenFileDialog d = new OpenFileDialog(RecordingActivity.this, OpenFileDialog.DIALOG_TYPE.FOLDER_DIALOG);
                    d.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            OnFlyEncoding fly = new OnFlyEncoding(recording.storage, recording.storage.getNewFile(d.getCurrentPath(), FormatWAV.EXT), recording.getInfo());
                            FileEncoder encoder = new FileEncoder(RecordingActivity.this, in, fly);
                            encoding(encoder, fly, new Runnable() {
                                @Override
                                public void run() {
                                    finish();
                                }
                            });
                        }
                    });
                    d.show();
                }
            });
        }
        builder.show();
    }

    @Override
    public int getAppTheme() {
        return AudioApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        showLocked(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_recording);

        pitch = (PitchView) findViewById(R.id.recording_pitch);
        time = (TextView) findViewById(R.id.recording_time);
        state = (TextView) findViewById(R.id.recording_state);
        title = (TextView) findViewById(R.id.recording_title);

        screen = new ScreenReceiver();
        screen.registerReceiver(this);

        receiver = new RecordingReceiver();
        receiver.filter.addAction(PAUSE_BUTTON);
        receiver.filter.addAction(ACTION_FINISH_RECORDING);
        receiver.registerReceiver(this);

        AudioApplication app = AudioApplication.from(this);
        try {
            if (app.recording == null)
                app.recording = new AudioApplication.RecordingStorage(this, pitch.getPitchTime());
            recording = app.recording;
            synchronized (recording.handlers) {
                recording.handlers.add(handler);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "onCreate", e);
            Toast.Error(this, e);
            finish();
            return;
        }

        sendBroadcast(new Intent(START_RECORDING));

        edit(false, false);

        title.setText(Storage.getName(this, recording.targetUri));

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        if (shared.getBoolean(AudioApplication.PREFERENCE_CALL, false)) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_CALL_STATE);
        }

        recording.updateBufferSize(false);

        loadSamples();

        final View cancel = findViewById(R.id.recording_cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancel.setClickable(false);
                cancelDialog(new Runnable() {
                    @Override
                    public void run() {
                        stopRecording();
                        if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
                            try {
                                if (recording.e != null) {
                                    recording.e.close();
                                    recording.e = null;
                                }
                            } catch (RuntimeException e) {
                                Error(e);
                            }
                            Storage.delete(RecordingActivity.this, recording.targetUri);
                        }
                        Storage.delete(recording.storage.getTempRecording());
                        finish();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        cancel.setClickable(true);
                    }
                });
            }
        });

        pause = (ImageButton) findViewById(R.id.recording_pause);
        pause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pauseButton();
            }
        });

        done = findViewById(R.id.recording_done);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (encoder != null)
                    return;
                String msg;
                if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
                    msg = getString(R.string.recording_status_recording);
                } else
                    msg = getString(R.string.recording_status_encoding);
                stopRecording(msg);
                try {
                    encoding(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                } catch (RuntimeException e) {
                    Error(e);
                }
            }
        });

        Intent intent = getIntent();
        String a = intent.getAction();
        if (a != null && a.equals(START_PAUSE)) { // pretend we already start it
            start = false;
            stopRecording(getString(R.string.recording_status_pause));
        }
        if (a != null && a.equals(ERROR))
            muted = new ErrorDialog(this, intent.getStringExtra("msg")).setTitle(intent.getStringExtra("title")).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    void loadSamples() {
        File f = recording.storage.getTempRecording();
        if (!f.exists()) {
            recording.samplesTime = 0;
            updateSamples(recording.samplesTime);
            return;
        }

        RawSamples rs = new RawSamples(f);
        recording.samplesTime = rs.getSamples() / Sound.getChannels(this);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int count = pitch.getMaxPitchCount(metrics.widthPixels);

        short[] buf = new short[count * recording.samplesUpdateStereo];
        long cut = recording.samplesTime * Sound.getChannels(this) - buf.length;

        if (cut < 0)
            cut = 0;

        rs.open(cut, buf.length);
        int len = rs.read(buf);
        rs.close();

        pitch.clear(cut / recording.samplesUpdateStereo);
        int lenUpdate = len / recording.samplesUpdateStereo * recording.samplesUpdateStereo; // cut right overs (leftovers from right)
        for (int i = 0; i < lenUpdate; i += recording.samplesUpdateStereo) {
            double dB = RawSamples.getDB(buf, i, recording.samplesUpdateStereo);
            pitch.add(dB);
        }
        updateSamples(recording.samplesTime);

        int diff = len - lenUpdate;
        if (diff > 0) {
            recording.dbBuffer = ShortBuffer.allocate(recording.samplesUpdateStereo);
            recording.dbBuffer.put(buf, lenUpdate, diff);
        }
    }

    void pauseButton() {
        if (recording.thread != null) {
            receiver.errors = false;
            stopRecording(getString(R.string.recording_status_pause));
            receiver.stopBluetooth();
            headset(true, false);
        } else {
            receiver.errors = true;
            receiver.stopBluetooth(); // reset bluetooth
            editCut();
            if (receiver.isRecordingReady())
                startRecording();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        recording.updateBufferSize(false);

        if (start) { // start once
            start = false;
            if (Storage.permitted(this, PERMISSIONS_AUDIO, RESULT_START)) { // audio perm
                if (receiver.isRecordingReady())
                    startRecording();
                else
                    stopRecording(getString(R.string.hold_by_bluetooth));
            }
        }

        boolean r = recording.thread != null;

        RecordingService.startService(this, Storage.getName(this, recording.targetUri), r, encoder != null, duration);

        if (r) {
            pitch.record();
        } else {
            if (editSample != -1)
                edit(true, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        recording.updateBufferSize(true);
        editPlay(false);
        pitch.stop();
    }

    void stopRecording(String status) {
        setState(status);
        pause.setImageResource(R.drawable.ic_mic_24dp);
        pause.setContentDescription(getString(R.string.record_button));

        stopRecording();

        RecordingService.startService(this, Storage.getName(this, recording.targetUri), false, encoder != null, duration);

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
            pitch.setOnTouchListener(null);
        } else {
            pitch.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    edit(true, true);
                    float x = event.getX();
                    if (x < 0)
                        x = 0;
                    long edit = pitch.edit(x);
                    if (edit == -1)
                        edit(false, false);
                    else
                        editSample = pitch.edit(x) * recording.samplesUpdate;
                    return true;
                }
            });
        }
    }

    void stopRecording() {
        if (recording != null) // not possible, but some devices do not call onCreate
            recording.stopRecording();
        AudioApplication.from(this).recording = null;
        handler.removeCallbacks(receiver.connected);
        pitch.stop();
        sendBroadcast(new Intent(STOP_RECORDING));
    }

    void edit(boolean show, boolean animate) {
        View box = findViewById(R.id.recording_edit_box);
        View cut = box.findViewById(R.id.recording_cut);
        final ImageView playButton = (ImageView) box.findViewById(R.id.recording_play);
        View done = box.findViewById(R.id.recording_edit_done);

        if (show) {
            setState(getString(R.string.recording_status_edit));
            editPlay(false);

            MarginBottomAnimation.apply(box, true, animate);

            cut.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editCut();
                }
            });

            playButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (play != null) {
                        editPlay(false);
                    } else {
                        editPlay(true);
                    }
                }
            });

            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(false, true);
                }
            });
        } else {
            editSample = -1;
            setState(getString(R.string.recording_status_pause));
            editPlay(false);
            pitch.edit(-1);
            pitch.stop();

            MarginBottomAnimation.apply(box, false, animate);
            cut.setOnClickListener(null);
            playButton.setOnClickListener(null);
            done.setOnClickListener(null);
        }
    }

    void setState(String s) {
        long free = 0;

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        int rate = Integer.parseInt(shared.getString(AudioApplication.PREFERENCE_RATE, ""));
        int m = Sound.getChannels(this);
        int c = Sound.DEFAULT_AUDIOFORMAT == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1;

        long perSec;

        String ext = shared.getString(AudioApplication.PREFERENCE_ENCODING, "");

        if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
            perSec = Factory.getEncoderRate(ext, recording.sampleRate);
            try {
                free = Storage.getFree(this, recording.targetUri);
            } catch (RuntimeException e) { // IllegalArgumentException
            }
        } else { // raw file on tmp device
            perSec = c * m * rate;
            try {
                free = Storage.getFree(recording.storage.getTempRecording());
            } catch (RuntimeException e) { // IllegalArgumentException
            }
        }

        long sec = free / perSec * 1000;

        state.setText(s + "\n(" + AudioApplication.formatFree(this, free, sec) + ")");
    }

    void editPlay(boolean show) {
        View box = findViewById(R.id.recording_edit_box);
        final ImageView playButton = (ImageView) box.findViewById(R.id.recording_play);

        if (show) {
            playButton.setImageResource(R.drawable.ic_pause_black_24dp);
            playButton.setContentDescription(getString(R.string.pause_button));

            int playUpdate = PitchView.UPDATE_SPEED * recording.sampleRate / 1000;

            RawSamples rs = new RawSamples(recording.storage.getTempRecording());
            int len = (int) (rs.getSamples() - editSample * Sound.getChannels(this)); // in samples

            final AudioTrack.OnPlaybackPositionUpdateListener listener = new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(android.media.AudioTrack track) {
                    editPlay(false);
                }

                @Override
                public void onPeriodicNotification(android.media.AudioTrack track) {
                    if (play != null) {
                        long now = System.currentTimeMillis();
                        long playIndex = editSample + (now - play.playStart) * recording.sampleRate / 1000;
                        pitch.play(playIndex / (float) recording.samplesUpdate);
                    }
                }
            };

            AudioTrack.AudioBuffer buf = new AudioTrack.AudioBuffer(recording.sampleRate, Sound.getOutMode(this), Sound.DEFAULT_AUDIOFORMAT, len);
            rs.open(editSample * Sound.getChannels(this), buf.len); // len in samples
            int r = rs.read(buf.buffer); // r in samples
            if (r != buf.len)
                throw new RuntimeException("unable to read data");
            int last = buf.len / buf.getChannels() - 1;
            if (play != null)
                play.release();
            play = AudioTrack.create(Sound.SOUND_STREAM, Sound.SOUND_CHANNEL, Sound.SOUND_TYPE, buf);
            play.setNotificationMarkerPosition(last);
            play.setPositionNotificationPeriod(playUpdate);
            play.setPlaybackPositionUpdateListener(listener, handler);
            play.play();
        } else {
            if (play != null) {
                play.release();
                play = null;
            }
            pitch.play(-1);
            playButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
            playButton.setContentDescription(getString(R.string.play_button));
        }
    }

    void editCut() {
        if (editSample == -1)
            return;

        RawSamples rs = new RawSamples(recording.storage.getTempRecording());
        rs.trunk((editSample + recording.samplesUpdate) * Sound.getChannels(this));
        rs.close();

        edit(false, true);
        loadSamples();
        pitch.drawCalc();
    }

    @Override
    public void onBackPressed() {
        cancelDialog(new Runnable() {
            @Override
            public void run() {
                stopRecording();
                Storage.delete(recording.storage.getTempRecording());
                finish();
            }
        }, null);
    }

    void cancelDialog(final Runnable run, final Runnable cancel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_cancel);
        builder.setMessage(R.string.are_you_sure);
        builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                run.run();
            }
        });
        builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (cancel != null)
                    cancel.run();
            }
        });
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");

        stopRecording();
        receiver.stopBluetooth();
        headset(false, false);

        if (muted != null) {
            muted.dismiss();
            muted = null;
        }

        if (screen != null) {
            screen.close();
            screen = null;
        }

        if (receiver != null) {
            receiver.close();
            receiver = null;
        }

        RecordingService.stopRecording(this);

        if (pscl != null) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_NONE);
            pscl = null;
        }

        if (play != null) {
            play.release();
            play = null;
        }

        if (encoder != null) {
            encoder.close();
            encoder = null;
        }

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = shared.edit();
        editor.remove(AudioApplication.PREFERENCE_TARGET);
        editor.commit();
    }

    void startRecording() {
        try {
            edit(false, true);
            pitch.setOnTouchListener(null);

            pause.setImageResource(R.drawable.ic_pause_black_24dp);
            pause.setContentDescription(getString(R.string.pause_button));

            pitch.record();

            setState(getString(R.string.recording_status_recording));

            headset(true, true);

            recording.startRecording();

            RecordingService.startService(this, Storage.getName(this, recording.targetUri), true, encoder != null, duration);
        } catch (RuntimeException e) {
            Toast.Error(RecordingActivity.this, e);
            finish();
        }
    }

    void updateSamples(long samplesTime) {
        long ms = samplesTime / recording.sampleRate * 1000;
        duration = AudioApplication.formatDuration(this, ms);
        time.setText(duration);
        RecordingService.startService(this, Storage.getName(this, recording.targetUri), recording.thread != null, encoder != null, duration);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case RESULT_START:
                if (Storage.permitted(this, permissions)) {
                    if (receiver.isRecordingReady())
                        startRecording();
                } else {
                    Toast.makeText(this, R.string.not_permitted, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    void encoding(final Runnable done) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(RecordingActivity.this);
        if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) { // keep encoder open if encoding on fly enabled
            try {
                if (recording.e != null) {
                    recording.e.close();
                    recording.e = null;
                }
            } catch (RuntimeException e) {
                Error(e);
                return;
            }
        }

        final File in = recording.storage.getTempRecording();

        final Runnable last = new Runnable() {
            @Override
            public void run() {
                SharedPreferences.Editor edit = shared.edit();
                edit.putString(AudioApplication.PREFERENCE_LAST, Storage.getName(RecordingActivity.this, recording.targetUri));
                edit.commit();
                done.run();
            }
        };

        if (!in.exists() || in.length() == 0) {
            last.run();
            return;
        }

        final OnFlyEncoding fly = new OnFlyEncoding(recording.storage, recording.targetUri, recording.getInfo());

        encoder = new FileEncoder(this, in, fly);

        if (shared.getBoolean(AudioApplication.PREFERENCE_VOICE, false))
            encoder.filters.add(new VoiceFilter(recording.getInfo()));
        float amp = shared.getFloat(AudioApplication.PREFERENCE_VOLUME, 1);
        if (amp != 1)
            encoder.filters.add(new AmplifierFilter(amp));
        if (shared.getBoolean(AudioApplication.PREFERENCE_SKIP, false))
            encoder.filters.add(new SkipSilenceFilter(recording.getInfo()));

        encoding(encoder, fly, last);
    }

    void encoding(final FileEncoder encoder, final OnFlyEncoding fly, final Runnable last) {
        RecordingService.startService(this, Storage.getName(this, fly.targetUri), recording.thread != null, encoder != null, duration);

        final ProgressDialog d = new ProgressDialog(this);
        d.setTitle(R.string.encoding_title);
        d.setMessage(".../" + Storage.getName(this, recording.targetUri));
        d.setMax(100);
        d.setCancelable(false);
        d.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        d.setIndeterminate(false);
        d.show();

        encoder.run(new Runnable() {
            @Override
            public void run() {
                d.setProgress(encoder.getProgress());
            }
        }, new Runnable() {
            @Override
            public void run() { // success
                Storage.delete(encoder.in); // delete raw recording
                last.run();
                d.cancel();
            }
        }, new Runnable() {
            @Override
            public void run() { // or error
                Storage.delete(RecordingActivity.this, fly.targetUri); // fly has fd, delete target manually
                d.cancel();
                Error(encoder.getException());
            }
        });
    }

    @Override
    public void finish() {
        super.finish();
        MainActivity.startActivity(this);
    }

    public void headset(boolean b, final boolean recording) {
        if (b) {
            if (msc == null) {
                Log.d(TAG, "headset mediabutton on");
                msc = new MediaSessionCompat(this, TAG, new ComponentName(this, RecordingActivity.RecordingReceiver.class), null);
                msc.setCallback(new MediaSessionCompat.Callback() {
                    @Override
                    public void onPlay() {
                        pauseButton();
                    }

                    @Override
                    public void onPause() {
                        pauseButton();
                    }

                    @Override
                    public void onStop() {
                        pauseButton();
                    }
                });
                msc.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
                msc.setActive(true);
                msc.setPlaybackState(new PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0, 1).build()); // bug, when after device reboots we have to set playing state to 'playing' to make mediabutton work
            }
            PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_STOP)
                    .setState(recording ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, 0, 1);
            msc.setPlaybackState(builder.build());
        } else {
            if (msc != null) {
                Log.d(TAG, "headset mediabutton off");
                msc.release();
                msc = null;
            }
        }
    }
}
