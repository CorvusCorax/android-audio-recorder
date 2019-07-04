package com.github.axet.audiorecorder.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;
import android.view.View;

import com.github.axet.androidlibrary.app.NotificationManagerCompat;
import com.github.axet.androidlibrary.widgets.NotificationChannelCompat;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.Encoder;
import com.github.axet.audiolibrary.encoders.FormatFLAC;
import com.github.axet.audiolibrary.encoders.FormatM4A;
import com.github.axet.audiolibrary.encoders.FormatOGG;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiorecorder.BuildConfig;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;

import java.io.File;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioApplication extends com.github.axet.audiolibrary.app.MainApplication {
    public static final String PREFERENCE_CONTROLS = "controls";
    public static final String PREFERENCE_TARGET = "target";
    public static final String PREFERENCE_FLY = "fly";
    public static final String PREFERENCE_SOURCE = "bluetooth";

    public static final String PREFERENCE_VERSION = "version";

    public static final String PREFERENCE_NEXT = "next";

    public NotificationChannelCompat channelStatus;
    public RecordingStorage recording;

    public static AudioApplication from(Context context) {
        return (AudioApplication) com.github.axet.audiolibrary.app.MainApplication.from(context);
    }

    public static class RecordingStorage {
        public static final int PINCH = 1;
        public static final int UPDATESAMPLES = 2;
        public static final int END = 3;
        public static final int ERROR = 4;
        public static final int MUTED = 5;
        public static final int UNMUTED = 6;
        public static final int PAUSED = 7;

        public Context context;
        public final ArrayList<Handler> handlers = new ArrayList<>();

        public Sound sound;
        public Storage storage;

        public Encoder e;

        public AtomicBoolean interrupt = new AtomicBoolean(); // nio throws ClosedByInterruptException if thread interrupted
        public Thread thread;
        public final Object bufferSizeLock = new Object(); // lock for bufferSize
        public int bufferSize; // dynamic buffer size. big for backgound recording. small for realtime view updates.
        public int sampleRate; // variable from settings. how may samples per second.
        public int samplesUpdate; // pitch size in samples. how many samples count need to update view. 4410 for 100ms update.
        public int samplesUpdateStereo; // samplesUpdate * number of channels
        public Uri targetUri = null; // output target file 2016-01-01 01.01.01.wav
        public long samplesTime; // how many samples passed for current recording, stereo = samplesTime * 2

        public ShortBuffer dbBuffer = null; // PinchView samples buffer

        public int pitchTime; // screen width

        public RecordingStorage(Context context, int pitchTime) {
            this.context = context;
            this.pitchTime = pitchTime;
            storage = new Storage(context);
            sound = new Sound(context);

            sampleRate = Sound.getSampleRate(context);
            samplesUpdate = (int) (pitchTime * sampleRate / 1000f);
            samplesUpdateStereo = samplesUpdate * Sound.getChannels(context);

            final SharedPreferences shared = android.preference.PreferenceManager.getDefaultSharedPreferences(context);

            if (storage.recordingPending()) {
                String file = shared.getString(AudioApplication.PREFERENCE_TARGET, null);
                if (file != null) {
                    if (file.startsWith(ContentResolver.SCHEME_CONTENT))
                        targetUri = Uri.parse(file);
                    else if (file.startsWith(ContentResolver.SCHEME_FILE))
                        targetUri = Uri.parse(file);
                    else
                        targetUri = Uri.fromFile(new File(file));
                }
            }
            if (targetUri == null)
                targetUri = storage.getNewFile();
            SharedPreferences.Editor editor = shared.edit();
            editor.putString(AudioApplication.PREFERENCE_TARGET, targetUri.toString());
            editor.commit();
        }

        public void startRecording() {
            sound.silent();

            final SharedPreferences shared = android.preference.PreferenceManager.getDefaultSharedPreferences(context);

            int user;

            if (shared.getString(AudioApplication.PREFERENCE_SOURCE, context.getString(R.string.source_mic)).equals(context.getString(R.string.source_raw))) {
                if (Sound.isUnprocessedSupported(context))
                    user = MediaRecorder.AudioSource.UNPROCESSED;
                else
                    user = MediaRecorder.AudioSource.VOICE_RECOGNITION;
            } else {
                user = MediaRecorder.AudioSource.MIC;
            }

            int[] ss = new int[]{
                    user,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
            };

            if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
                final OnFlyEncoding fly = new OnFlyEncoding(storage, targetUri, getInfo());
                if (e == null) { // do not recreate encoder if on-fly mode enabled
                    e = new Encoder() {
                        @Override
                        public void encode(short[] buf, int pos, int len) {
                            fly.encode(buf, pos, len);
                        }

                        @Override
                        public void close() {
                            fly.close();
                        }
                    };
                }
            } else {
                final RawSamples rs = new RawSamples(storage.getTempRecording());
                rs.open(samplesTime * Sound.getChannels(context));
                e = new Encoder() {
                    @Override
                    public void encode(short[] buf, int pos, int len) {
                        rs.write(buf, pos, len);
                    }

                    @Override
                    public void close() {
                        rs.close();
                    }
                };
            }

            final AudioRecord recorder = Sound.createAudioRecorder(context, sampleRate, ss, 0);

            final Thread old = thread;
            final AtomicBoolean oldb = interrupt;

            interrupt = new AtomicBoolean(false);

            thread = new Thread("RecordingThread") {
                @Override
                public void run() {
                    if (old != null) {
                        oldb.set(true);
                        old.interrupt();
                        try {
                            old.join();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }

                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    PowerManager.WakeLock wlcpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":recordinglock");
                    wlcpu.acquire();

                    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

                    boolean silenceDetected = false;
                    long silence = samplesTime; // last non silence frame

                    long start = System.currentTimeMillis(); // recording start time
                    long session = 0; // samples count from start of recording

                    try {
                        long last = System.currentTimeMillis();
                        recorder.startRecording();

                        int samplesTimeCount = 0;
                        final int samplesTimeUpdate = 1000 * sampleRate / 1000; // how many samples we need to update 'samples'. time clock. every 1000ms.

                        short[] buffer = null;

                        boolean stableRefresh = false;

                        while (!interrupt.get()) {
                            synchronized (bufferSizeLock) {
                                if (buffer == null || buffer.length != bufferSize)
                                    buffer = new short[bufferSize];
                            }

                            int readSize = recorder.read(buffer, 0, buffer.length);
                            if (readSize < 0)
                                return;
                            long now = System.currentTimeMillis();
                            long diff = (now - last) * sampleRate / 1000;
                            last = now;

                            int samples = readSize / Sound.getChannels(context); // mono samples (for booth channels)

                            if (stableRefresh || diff >= samples) {
                                stableRefresh = true;

                                e.encode(buffer, 0, readSize);

                                short[] dbBuf;
                                int dbSize;
                                int readSizeUpdate;
                                if (dbBuffer != null) {
                                    ShortBuffer bb = ShortBuffer.allocate(dbBuffer.position() + readSize);
                                    dbBuffer.flip();
                                    bb.put(dbBuffer);
                                    bb.put(buffer, 0, readSize);
                                    dbBuf = new short[bb.position()];
                                    dbSize = dbBuf.length;
                                    bb.flip();
                                    bb.get(dbBuf, 0, dbBuf.length);
                                } else {
                                    dbBuf = buffer;
                                    dbSize = readSize;
                                }
                                readSizeUpdate = dbSize / samplesUpdateStereo * samplesUpdateStereo;
                                for (int i = 0; i < readSizeUpdate; i += samplesUpdateStereo) {
                                    double a = RawSamples.getAmplitude(dbBuf, i, samplesUpdateStereo);
                                    if (a != 0)
                                        silence = samplesTime + (i + samplesUpdateStereo) / Sound.getChannels(context);
                                    double dB = RawSamples.getDB(a);
                                    Post(PINCH, dB);
                                }
                                int readSizeLen = dbSize - readSizeUpdate;
                                if (readSizeLen > 0) {
                                    dbBuffer = ShortBuffer.allocate(readSizeLen);
                                    dbBuffer.put(dbBuf, readSizeUpdate, readSizeLen);
                                } else {
                                    dbBuffer = null;
                                }

                                samplesTime += samples;
                                samplesTimeCount += samples;
                                if (samplesTimeCount > samplesTimeUpdate) {
                                    Post(UPDATESAMPLES, samplesTime);
                                    samplesTimeCount -= samplesTimeUpdate;
                                }
                                session += samples;

                                if (samplesTime - silence > 2 * sampleRate) { // 2 second of mic muted
                                    if (!silenceDetected) {
                                        silenceDetected = true;
                                        Post(MUTED, null);
                                    }
                                } else {
                                    if (silenceDetected) {
                                        silenceDetected = false;
                                        Post(UNMUTED, null);
                                    }
                                }
                                diff = (now - start) * sampleRate / 1000; // number of samples we expect by this moment
                                if (diff - session > 2 * sampleRate) { // 2 second of silence / paused by os
                                    Post(PAUSED, null);
                                    session = diff; // reset
                                }
                            }
                        }
                    } catch (final RuntimeException e) {
                        Post(e);
                    } finally {
                        wlcpu.release();

                        // redraw view, we may add one last pich which is not been drawen because draw tread already interrupted.
                        // to prevent resume recording jump - draw last added pitch here.
                        Post(END, null);

                        if (recorder != null)
                            recorder.release();

                        if (!shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) { // keep encoder open if encoding on fly enabled
                            try {
                                if (e != null) {
                                    e.close();
                                    e = null;
                                }
                            } catch (RuntimeException e) {
                                Post(e);
                            }
                        }
                    }
                }
            };
            thread.start();
        }

        public void stopRecording() {
            if (thread != null) {
                interrupt.set(true);
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                thread = null;
            }
            sound.unsilent();
        }

        public RawSamples.Info getInfo() {
            return new RawSamples.Info(sampleRate, Sound.getChannels(context));
        }

        // calcuale buffer length dynamically, this way we can reduce thread cycles when activity in background
        // or phone screen is off.
        public void updateBufferSize(boolean pause) {
            synchronized (bufferSizeLock) {
                int samplesUpdate;

                if (pause) {
                    // we need make buffer multiply of pitch.getPitchTime() (100 ms).
                    // to prevent missing blocks from view otherwise:

                    // file may contain not multiply 'samplesUpdate' count of samples. it is about 100ms.
                    // we can't show on pitchView sorter then 100ms samples. we can't add partial sample because on
                    // resumeRecording we have to apply rest of samplesUpdate or reload all samples again
                    // from file. better then confusing user we cut them on next resumeRecording.

                    long l = 1000L / pitchTime * pitchTime;
                    samplesUpdate = (int) (l * sampleRate / 1000.0);
                } else {
                    samplesUpdate = this.samplesUpdate;
                }

                bufferSize = samplesUpdate * Sound.getChannels(context);
            }
        }

        public boolean isForeground() {
            synchronized (bufferSizeLock) {
                return bufferSize == this.samplesUpdate * Sound.getChannels(context);
            }
        }

        public void Post(Exception e) {
            Post(ERROR, e);
        }

        public void Post(int what, Object p) {
            synchronized (handlers) {
                for (Handler h : handlers)
                    h.obtainMessage(what, p).sendToTarget();
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        channelStatus = new NotificationChannelCompat(this, "status", "Status", NotificationManagerCompat.IMPORTANCE_LOW);

        switch (getVersion(PREFERENCE_VERSION, R.xml.pref_general)) {
            case -1:
                SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor edit = shared.edit();
                if (!FormatOGG.supported(this)) {
                    if (Build.VERSION.SDK_INT >= 18)
                        edit.putString(AudioApplication.PREFERENCE_ENCODING, FormatM4A.EXT);
                    else
                        edit.putString(AudioApplication.PREFERENCE_ENCODING, FormatFLAC.EXT);
                }
                edit.putInt(PREFERENCE_VERSION, 4);
                edit.commit();
                break;
            case 0:
                version_0_to_1();
                version_1_to_2();
                break;
            case 1:
                version_1_to_2();
                break;
            case 2:
                version_2_to_3();
                break;
            case 3:
                version_3_to_4();
                break;
        }
    }

    void version_0_to_1() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putFloat(PREFERENCE_VOLUME, shared.getFloat(PREFERENCE_VOLUME, 0) + 1); // update volume from 0..1 to 0..1..4
        edit.putInt(PREFERENCE_VERSION, 1);
        edit.commit();
    }

    void show(String title, String text) {
        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteNotificationCompat.Builder builder = new RemoteNotificationCompat.Builder(this, R.layout.notifictaion);
        builder.setViewVisibility(R.id.notification_record, View.GONE);
        builder.setViewVisibility(R.id.notification_pause, View.GONE);
        builder.setTheme(AudioApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark))
                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
                .setTitle(title)
                .setText(text)
                .setMainIntent(main)
                .setChannel(channelStatus)
                .setSmallIcon(R.drawable.ic_launcher_notification);
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    @SuppressLint("RestrictedApi")
    void version_1_to_2() {
        Locale locale = Locale.getDefault();
        if (locale.toString().startsWith("ru")) {
            String title = "Программа переименована";
            String text = "'Аудио Рекордер' -> '" + getString(R.string.app_name) + "'";
            show(title, text);
        }
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 2);
        edit.commit();
    }

    @SuppressLint("RestrictedApi")
    void version_2_to_3() {
        Locale locale = Locale.getDefault();
        if (locale.toString().startsWith("tr")) {
            String title = "Application renamed";
            String text = "'Audio Recorder' -> '" + getString(R.string.app_name) + "'";
            show(title, text);
        }
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 3);
        edit.commit();
    }

    @SuppressLint("RestrictedApi")
    void version_3_to_4() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.remove(PREFERENCE_SORT);
        edit.putInt(PREFERENCE_VERSION, 4);
        edit.commit();
    }
}
