package com.github.axet.audiorecorder.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.axet.androidlibrary.animations.MarginBottomAnimation;
import com.github.axet.androidlibrary.sound.AudioTrack;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.app.Recordings;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.Encoder;
import com.github.axet.audiolibrary.encoders.EncoderInfo;
import com.github.axet.audiolibrary.encoders.Factory;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.widgets.PitchView;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.MainApplication;
import com.github.axet.audiorecorder.app.Storage;
import com.github.axet.audiorecorder.services.RecordingService;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class RecordingActivity extends AppCompatActivity {
    public static final String TAG = RecordingActivity.class.getSimpleName();

    public static String START_PAUSE = RecordingActivity.class.getCanonicalName() + ".START_PAUSE";
    public static String PAUSE_BUTTON = RecordingActivity.class.getCanonicalName() + ".PAUSE_BUTTON";

    PhoneStateChangeListener pscl = new PhoneStateChangeListener();
    Handler handle = new Handler();
    FileEncoder encoder;

    // do we need to start recording immidiatly?
    boolean start = true;

    Thread thread;
    // lock for bufferSize
    final Object bufferSizeLock = new Object();
    // dynamic buffer size. big for backgound recording. small for realtime view updates.
    int bufferSize;
    // variable from settings. how may samples per second.
    int sampleRate;
    // pitch size in samples. how many samples count need to update view. 4410 for 100ms update.
    int samplesUpdate;
    // output target file 2016-01-01 01.01.01.wav
    Uri targetUri = null;
    // how many samples passed for current recording
    long samplesTime;
    // current cut position in samples from begining of file
    long editSample = -1;

    // current play sound track
    AudioTrack play;

    TextView title;
    TextView time;
    TextView state;
    ImageButton pause;
    PitchView pitch;

    Storage storage;
    Sound sound;
    RecordingReceiver receiver;
    Handler handler = new Handler();

    ShortBuffer dbBuffer = null;

    public static void startActivity(Context context, boolean pause) {
        Intent i = new Intent(context, RecordingActivity.class);
        if (pause) {
            i.setAction(RecordingActivity.START_PAUSE);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(i);
    }

    class RecordingReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(PAUSE_BUTTON)) {
                pauseButton();
            }
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
                    if (thread != null) {
                        stopRecording(getString(R.string.hold_by_call));
                        pausedByCall = true;
                    }
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    if (pausedByCall) {
                        startRecording();
                    }
                    wasRinging = false;
                    pausedByCall = false;
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(((MainApplication) getApplication()).getUserTheme());
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_recording);

        setupActionBar();

        pitch = (PitchView) findViewById(R.id.recording_pitch);
        time = (TextView) findViewById(R.id.recording_time);
        state = (TextView) findViewById(R.id.recording_state);
        title = (TextView) findViewById(R.id.recording_title);

        storage = new Storage(this);
        sound = new Sound(this);

        edit(false, false);

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        try {
            if (storage.recordingPending()) {
                String file = shared.getString(MainApplication.PREFERENCE_TARGET, null);
                if (file != null) {
                    if (file.startsWith(ContentResolver.SCHEME_CONTENT))
                        targetUri = Uri.parse(file);
                    else
                        targetUri = Uri.fromFile(new File(file));
                }
            }
            if (targetUri == null)
                targetUri = storage.getNewFile();
            SharedPreferences.Editor editor = shared.edit();
            editor.putString(MainApplication.PREFERENCE_TARGET, targetUri.toString());
            editor.commit();
        } catch (RuntimeException e) {
            Log.d(TAG, "onCreate", e);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(storage.getDocumentName(targetUri));

        if (shared.getBoolean(MainApplication.PREFERENCE_CALL, false)) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_CALL_STATE);
        }

        sampleRate = Integer.parseInt(shared.getString(MainApplication.PREFERENCE_RATE, ""));
        sampleRate = Sound.getValidRecordRate(MainApplication.getInMode(this), sampleRate);
        if (sampleRate == -1) {
            Toast.makeText(this, "Unable to initailze audio", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        samplesUpdate = (int) (pitch.getPitchTime() * sampleRate / 1000.0);

        updateBufferSize(false);

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
                        storage.delete(storage.getTempRecording());
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

        final View done = findViewById(R.id.recording_done);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (encoder != null)
                    return;
                stopRecording(getString(R.string.encoding));
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

        String a = getIntent().getAction();
        if (a != null && a.equals(START_PAUSE)) {
            // pretend we already start it
            start = false;
            stopRecording(getString(R.string.pause));
        }

        receiver = new RecordingReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(PAUSE_BUTTON);
        registerReceiver(receiver, filter);
    }

    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
//            actionBar.setBackgroundDrawable(new ColorDrawable(MainApplication.getActionbarColor(this)));
        }
    }

    void loadSamples() {
        if (!storage.getTempRecording().exists()) {
            updateSamples(0);
            return;
        }

        RawSamples rs = new RawSamples(storage.getTempRecording());
        samplesTime = rs.getSamples() / MainApplication.getChannels(this);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int count = pitch.getMaxPitchCount(metrics.widthPixels);

        short[] buf = new short[count * samplesUpdate];
        long cut = samplesTime - buf.length;

        if (cut < 0)
            cut = 0;

        rs.open(cut, buf.length);
        int len = rs.read(buf);
        rs.close();

        int samplesUpdateStereo = samplesUpdate * MainApplication.getChannels(this);
        pitch.clear(cut / samplesUpdateStereo);
        int lenUpdate = len / samplesUpdateStereo * samplesUpdateStereo; // cut right overs (leftovers from right)
        for (int i = 0; i < lenUpdate; i += samplesUpdateStereo) {
            double dB = RawSamples.getDB(buf, i, samplesUpdateStereo);
            pitch.add(dB);
        }
        updateSamples(samplesTime);

        int diff = len - lenUpdate;
        if (diff > 0) {
            dbBuffer = ShortBuffer.allocate(samplesUpdateStereo);
            dbBuffer.put(buf, lenUpdate, diff);
        }
    }

    void pauseButton() {
        if (thread != null) {
            stopRecording(getString(R.string.pause));
        } else {
            editCut();

            startRecording();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        updateBufferSize(false);

        // start once
        if (start) {
            start = false;
            if (permitted()) {
                startRecording();
            }
        }

        boolean recording = thread != null;

        RecordingService.startService(this, storage.getDocumentName(targetUri), recording, encoder != null);

        if (recording) {
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
        updateBufferSize(true);
        editPlay(false);
        pitch.stop();
    }

    void stopRecording(String status) {
        setState(status);
        pause.setImageResource(R.drawable.ic_mic_24dp);

        stopRecording();

        RecordingService.startService(this, storage.getDocumentName(targetUri), thread != null, encoder != null);

        pitch.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                edit(true, true);
                float x = event.getX();
                if (x < 0)
                    x = 0;
                editSample = pitch.edit(x) * samplesUpdate * MainApplication.getChannels(RecordingActivity.this);
                return true;
            }
        });
    }

    void stopRecording() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        pitch.stop();
        sound.unsilent();
    }

    void edit(boolean show, boolean animate) {
        if (show) {
            setState(getString(R.string.edit));
            editPlay(false);

            View box = findViewById(R.id.recording_edit_box);
            MarginBottomAnimation.apply(box, true, animate);

            View cut = box.findViewById(R.id.recording_cut);
            cut.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editCut();
                }
            });

            final ImageView playButton = (ImageView) box.findViewById(R.id.recording_play);
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

            View done = box.findViewById(R.id.recording_edit_done);
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(false, true);
                }
            });
        } else {
            editSample = -1;
            setState(getString(R.string.pause));
            editPlay(false);
            pitch.edit(-1);
            pitch.stop();

            View box = findViewById(R.id.recording_edit_box);
            MarginBottomAnimation.apply(box, false, animate);
        }
    }

    void setState(String s) {
        long free = Storage.getFree(storage.getTempRecording());

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        int rate = Integer.parseInt(shared.getString(MainApplication.PREFERENCE_RATE, ""));
        int m = MainApplication.getChannels(this);
        int c = Sound.DEFAULT_AUDIOFORMAT == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1;

        long perSec = (c * m * rate);
        long sec = free / perSec * 1000;

        state.setText(s + "\n(" + MainApplication.formatFree(this, free, sec) + ")");
    }

    void editPlay(boolean show) {
        View box = findViewById(R.id.recording_edit_box);
        final ImageView playButton = (ImageView) box.findViewById(R.id.recording_play);

        if (show) {
            playButton.setImageResource(R.drawable.ic_pause_black_24dp);

            int playUpdate = PitchView.UPDATE_SPEED * sampleRate / 1000;

            RawSamples rs = new RawSamples(storage.getTempRecording());
            int len = (int) (rs.getSamples() - editSample); // in samples

            final AudioTrack.OnPlaybackPositionUpdateListener listener = new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(android.media.AudioTrack track) {
                    editPlay(false);
                }

                @Override
                public void onPeriodicNotification(android.media.AudioTrack track) {
                    if (play != null) {
                        long now = System.currentTimeMillis();
                        long playIndex = editSample / MainApplication.getChannels(RecordingActivity.this) + (now - play.playStart) * sampleRate / 1000;
                        pitch.play(playIndex / (float) samplesUpdate);
                    }
                }
            };

            AudioTrack.AudioBuffer buf = new AudioTrack.AudioBuffer(sampleRate, MainApplication.getOutMode(this), Sound.DEFAULT_AUDIOFORMAT, len);
            rs.open(editSample, buf.len); // len in samples
            int r = rs.read(buf.buffer); // r in samples
            if (r != buf.len)
                throw new RuntimeException("unable to read data");
            int last = buf.len / buf.getChannels() - 1;
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
        }
    }

    void editCut() {
        if (editSample == -1)
            return;

        RawSamples rs = new RawSamples(storage.getTempRecording());
        rs.trunk(editSample + samplesUpdate * MainApplication.getChannels(this));
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
                storage.delete(storage.getTempRecording());
                finish();
            }
        }, null);
    }

    void cancelDialog(final Runnable run, final Runnable cancel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_cancel);
        builder.setMessage(R.string.are_you_sure_cancel);
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

        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }

        RecordingService.stopRecording(this);

        if (pscl != null) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_NONE);
            pscl = null;
        }

        if (encoder != null) {
            encoder.close();
            encoder = null;
        }

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = shared.edit();
        editor.remove(MainApplication.PREFERENCE_TARGET);
    }

    void startRecording() {
        edit(false, true);
        pitch.setOnTouchListener(null);

        setState(getString(R.string.recording));

        sound.silent();

        pause.setImageResource(R.drawable.ic_pause_black_24dp);

        pitch.record();

        if (thread != null) {
            thread.interrupt();
        }

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                int p = android.os.Process.getThreadPriority(android.os.Process.myTid());

                if (p != android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) {
                    Log.e(TAG, "Unable to set Thread Priority " + android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                }

                RawSamples rs = null;
                AudioRecord recorder = null;
                try {
                    rs = new RawSamples(storage.getTempRecording());

                    rs.open(samplesTime);

                    int c = MainApplication.getInMode(RecordingActivity.this);
                    int min = AudioRecord.getMinBufferSize(sampleRate, c, Sound.DEFAULT_AUDIOFORMAT);
                    if (min <= 0)
                        throw new RuntimeException("Unable to initialize AudioRecord: Bad audio values");
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, c, Sound.DEFAULT_AUDIOFORMAT, min * 2);
                    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, c, Sound.DEFAULT_AUDIOFORMAT, min * 2);
                        if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                            throw new RuntimeException("Unable to initialize AudioRecord");
                    }

                    long start = System.currentTimeMillis();
                    recorder.startRecording();

                    int samplesTimeCount = 0;
                    // how many samples we need to update 'samples'. time clock. every 1000ms.
                    int samplesTimeUpdate = 1000 / 1000 * sampleRate;

                    short[] buffer = null;

                    boolean stableRefresh = false;

                    int samplesUpdateStereo = samplesUpdate * MainApplication.getChannels(RecordingActivity.this);

                    while (!Thread.currentThread().isInterrupted()) {
                        synchronized (bufferSizeLock) {
                            if (buffer == null || buffer.length != bufferSize)
                                buffer = new short[bufferSize];
                        }

                        int readSize = recorder.read(buffer, 0, buffer.length);
                        if (readSize <= 0) {
                            break;
                        }
                        long end = System.currentTimeMillis();

                        long diff = (end - start) * sampleRate / 1000;

                        start = end;

                        int samples = readSize / MainApplication.getChannels(RecordingActivity.this);

                        if (stableRefresh || diff >= samples) {
                            stableRefresh = true;

                            rs.write(buffer, 0, readSize);

                            short[] dbBuf;
                            int readSizeUpdate;
                            if (dbBuffer != null) {
                                ShortBuffer bb = ShortBuffer.allocate(dbBuffer.position() + readSize);
                                dbBuffer.flip();
                                bb.put(dbBuffer);
                                bb.put(buffer, 0, readSize);
                                dbBuf = new short[bb.position()];
                                readSize = dbBuf.length;
                                bb.flip();
                                bb.get(dbBuf, 0, dbBuf.length);
                            } else {
                                dbBuf = buffer;
                            }
                            readSizeUpdate = readSize / samplesUpdateStereo * samplesUpdateStereo;
                            for (int i = 0; i < readSizeUpdate; i += samplesUpdateStereo) {
                                final double dB = RawSamples.getDB(dbBuf, i, samplesUpdateStereo);
                                handle.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        pitch.add(dB);
                                    }
                                });
                            }
                            int readSizeLen = readSize - readSizeUpdate;
                            if (readSizeLen > 0) {
                                dbBuffer = ShortBuffer.allocate(readSizeLen);
                                dbBuffer.put(dbBuf, readSizeUpdate, readSizeLen);
                            } else {
                                dbBuffer = null;
                            }

                            samplesTime += samples;
                            samplesTimeCount += samples;
                            if (samplesTimeCount > samplesTimeUpdate) {
                                final long m = samplesTime;
                                handle.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateSamples(m);
                                    }
                                });
                                samplesTimeCount -= samplesTimeUpdate;
                            }
                        }
                    }
                } catch (final RuntimeException e) {
                    handle.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.e(TAG, Log.getStackTraceString(e));
                            Toast.makeText(RecordingActivity.this, "AudioRecord error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                } finally {
                    // redraw view, we may add one last pich which is not been drawen because draw tread already interrupted.
                    // to prevent resume recording jump - draw last added pitch here.
                    handle.post(new Runnable() {
                        @Override
                        public void run() {
                            pitch.drawEnd();
                        }
                    });

                    if (rs != null)
                        rs.close();

                    if (recorder != null)
                        recorder.release();
                }
            }
        }, "RecordingThread");
        thread.start();

        RecordingService.startService(this, storage.getDocumentName(targetUri), thread != null, encoder != null);
    }

    // calcuale buffer length dynamically, this way we can reduce thread cycles when activity in background
    // or phone screen is off.
    void updateBufferSize(boolean pause) {
        synchronized (bufferSizeLock) {
            int samplesUpdate;

            if (pause) {
                // we need make buffer multiply of pitch.getPitchTime() (100 ms).
                // to prevent missing blocks from view otherwise:

                // file may contain not multiply 'samplesUpdate' count of samples. it is about 100ms.
                // we can't show on pitchView sorter then 100ms samples. we can't add partial sample because on
                // resumeRecording we have to apply rest of samplesUpdate or reload all samples again
                // from file. better then confusing user we cut them on next resumeRecording.

                long l = 1000;
                l = l / pitch.getPitchTime() * pitch.getPitchTime();
                samplesUpdate = (int) (l * sampleRate / 1000.0);
            } else {
                samplesUpdate = this.samplesUpdate;
            }

            bufferSize = samplesUpdate * MainApplication.getChannels(this);
        }
    }

    void updateSamples(long samplesTime) {
        long ms = samplesTime / sampleRate * 1000;
        time.setText(MainApplication.formatDuration(this, ms));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case 1:
                if (permitted(permissions)) {
                    startRecording();
                } else {
                    Toast.makeText(this, R.string.not_permitted, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    public static final String[] PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO
    };

    boolean permitted(String[] ss) {
        if (Build.VERSION.SDK_INT < 15)
            return true;
        for (String s : ss) {
            if (ContextCompat.checkSelfPermission(this, s) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    boolean permitted() {
        if (Build.VERSION.SDK_INT < 15)
            return true;
        for (String s : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, s) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
                return false;
            }
        }
        return true;
    }

    EncoderInfo getInfo() {
        final int channels = MainApplication.getChannels(this);
        final int bps = Sound.DEFAULT_AUDIOFORMAT == AudioFormat.ENCODING_PCM_16BIT ? 16 : 8;
        return new EncoderInfo(channels, sampleRate, bps);
    }

    void encoding(final Runnable run) {
        final File in = storage.getTempRecording();
        final File out;

        final String s = targetUri.getScheme();
        if (s.startsWith(ContentResolver.SCHEME_CONTENT)) {
            out = storage.getTempEncoding();
        } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
            out = new File(targetUri.getPath());
        } else {
            throw new RuntimeException("unkonwn uri");
        }

        File parent = out.getParentFile();

        if (!parent.exists()) {
            if (!parent.mkdirs()) { // in case if it were manually deleted
                throw new RuntimeException("Unable to create: " + parent);
            }
        }

        EncoderInfo info = getInfo();

        Encoder e = null;

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        String ext = shared.getString(MainApplication.PREFERENCE_ENCODING, "");

        e = Factory.getEncoder(this, ext, info, out);

        encoder = new FileEncoder(this, in, e);

        RecordingService.startService(this, storage.getDocumentName(targetUri), thread != null, encoder != null);

        final ProgressDialog d = new ProgressDialog(this);
        d.setTitle(R.string.encoding_title);
        d.setMessage(".../" + storage.getDocumentName(targetUri));
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
                d.cancel();

                if (s.startsWith(ContentResolver.SCHEME_CONTENT)) {
                    ContentResolver resolver = getContentResolver();
                    try {
                        InputStream is = new FileInputStream(out);
                        OutputStream os = resolver.openOutputStream(targetUri);
                        IOUtils.copy(is, os);
                        is.close();
                        os.close();
                        storage.delete(out); // delete tmp encoding file
                    } catch (IOException e) {
                        storage.delete(out); // delete tmp encoding file
                        try {
                            storage.delete(targetUri); // delete SAF encoding file
                        } catch (RuntimeException ee) {
                            Log.d(TAG, "unable to delete target uri", e); // ignore, not even created?
                        }
                        Error(e);
                        d.cancel();
                        return;
                    }
                }

                storage.delete(in); // delete raw recording

                SharedPreferences.Editor edit = shared.edit();
                edit.putString(MainApplication.PREFERENCE_LAST, Storage.getDocumentName(targetUri));
                edit.commit();

                run.run();
            }
        }, new Runnable() {
            @Override
            public void run() { // done
                d.cancel();
                Error(encoder.getException());
            }
        });
    }

    void Error(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            Throwable t;
            if (encoder == null) {
                t = e;
            } else {
                t = encoder.getException();
                if (t == null)
                    t = e;
            }
            while (t.getCause() != null)
                t = t.getCause();
            msg = t.getClass().getSimpleName();
        }
        Error(msg);
    }

    void Error(String msg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Error");
        builder.setMessage(msg);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                finish();
            }
        });
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        builder.show();
    }

    @Override
    public void finish() {
        super.finish();
        MainActivity.startActivity(this);
    }
}
