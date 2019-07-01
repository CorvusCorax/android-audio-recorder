package com.github.axet.audiorecorder.activities;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.github.axet.androidlibrary.services.StorageProvider;
import com.github.axet.androidlibrary.widgets.AboutPreferenceCompat;
import com.github.axet.androidlibrary.widgets.AppCompatThemeActivity;
import com.github.axet.androidlibrary.widgets.ErrorDialog;
import com.github.axet.androidlibrary.widgets.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.widgets.SearchView;
import com.github.axet.audiolibrary.app.Storage;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Recordings;
import com.github.axet.audiorecorder.services.RecordingService;

public class MainActivity extends AppCompatThemeActivity {
    public final static String TAG = MainActivity.class.getSimpleName();

    public static final int RESULT_PERMS = 1;

    FloatingActionButton fab;

    RecyclerView list;
    Recordings recordings;
    Storage storage;

    ScreenReceiver receiver;

    public static void startActivity(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(i);
    }

    @Override
    public int getAppTheme() {
        return AudioApplication.getTheme(this, R.style.RecThemeLight_NoActionBar, R.style.RecThemeDark_NoActionBar);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_main);

        storage = new Storage(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recordings.select(-1);
                finish();
                RecordingActivity.startActivity(MainActivity.this, false);
            }
        });


        list = (RecyclerView) findViewById(R.id.list);
        recordings = new Recordings(this, list) {
            @Override
            public boolean getPrefCall() {
                final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
                return shared.getBoolean(AudioApplication.PREFERENCE_CALL, false);
            }

            @Override
            public void showDialog(AlertDialog.Builder e) {
                AlertDialog d = e.create();
                showDialogLocked(d.getWindow());
                d.show();
            }
        };
        recordings.setEmptyView(findViewById(R.id.empty_list));
        list.setAdapter(recordings.empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recordings.setToolbar((ViewGroup) findViewById(R.id.recording_toolbar));

        receiver = new ScreenReceiver() {
            @Override
            public void onScreenOff() {
                boolean p = storage.recordingPending();
                boolean c = shared.getBoolean(AudioApplication.PREFERENCE_CONTROLS, false);
                if (!p && !c)
                    return;
                super.onScreenOff();
            }
        };
        receiver.registerReceiver(this);

        RecordingService.startIfPending(this);

        try {
            new Recordings.ExoLoader(this, false);
        } catch (Exception e) {
            Log.e(TAG, "error", e);
        }
    }

    void checkPending() {
        if (storage.recordingPending()) {
            finish();
            RecordingActivity.startActivity(MainActivity.this, true);
            return;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.menu_main, menu);

        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (myKM.inKeyguardRestrictedInputMode())
            menu.findItem(R.id.action_settings).setVisible(false);

        MenuItem item = menu.findItem(R.id.action_show_folder);
        Intent intent = StorageProvider.openFolderIntent(this, storage.getStoragePath());
        item.setIntent(intent);
        if (!StorageProvider.isFolderCallable(this, intent, StorageProvider.getProvider().getAuthority()))
            item.setVisible(false);

        MenuItem search = menu.findItem(R.id.action_search);
        final SearchView searchView = (SearchView) MenuItemCompat.getActionView(search);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();
                recordings.search(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                recordings.searchClose();
                return true;
            }
        });

        recordings.onCreateOptionsMenu(menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (recordings.onOptionsItemSelected(this, item))
            return true;

        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.action_about:
                AboutPreferenceCompat.showDialog(this, R.raw.about);
                return true;
            case R.id.action_show_folder:
                Intent intent = item.getIntent();
                startActivity(intent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        invalidateOptionsMenu(); // update storage folder intent

        try {
            storage.migrateLocalStorage();
        } catch (Exception e) {
            ErrorDialog.Error(this, e);
        }

        final String last = shared.getString(AudioApplication.PREFERENCE_LAST, "");
        Runnable done = new Runnable() {
            @Override
            public void run() {
                final int selected = getLastRecording(last);
                recordings.progressEmpty.setVisibility(View.GONE);
                recordings.progressText.setVisibility(View.VISIBLE);
                if (selected != -1) {
                    recordings.select(selected);
                    list.smoothScrollToPosition(selected);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            list.scrollToPosition(selected);
                        }
                    });
                }
            }
        };
        recordings.progressEmpty.setVisibility(View.VISIBLE);
        recordings.progressText.setVisibility(View.GONE);

        recordings.load(!last.isEmpty(), done);

        if (OptimizationPreferenceCompat.needKillWarning(this, AudioApplication.PREFERENCE_NEXT)) {
            AlertDialog.Builder muted;
            if (Build.VERSION.SDK_INT >= 28)
                muted = new ErrorDialog(this, getString(R.string.optimization_killed) + "\n\n" + getString(R.string.mic_muted_pie)).setTitle("Error");
            else
                muted = new ErrorDialog(this, getString(R.string.optimization_killed)).setTitle("Error");
            muted.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    checkPending();
                }
            });
            muted.show();
        } else {
            checkPending();
        }

        updateHeader();
    }

    int getLastRecording(String last) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        for (int i = 0; i < recordings.getItemCount(); i++) {
            Storage.RecordingUri f = recordings.getItem(i);
            if (f.name.equals(last)) {
                SharedPreferences.Editor edit = shared.edit();
                edit.putString(AudioApplication.PREFERENCE_LAST, "");
                edit.commit();
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case RESULT_PERMS:
                if (Storage.permitted(MainActivity.this, permissions)) {
                    try {
                        storage.migrateLocalStorage();
                    } catch (RuntimeException e) {
                        ErrorDialog.Error(MainActivity.this, e);
                    }
                    recordings.load(false, null);
                    checkPending();
                } else {
                    Toast.makeText(this, R.string.not_permitted, Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recordings.close();
        receiver.close();
    }

    void updateHeader() {
        Uri uri = storage.getStoragePath();
        long free = Storage.getFree(this, uri);
        long sec = Storage.average(this, free);
        TextView text = (TextView) findViewById(R.id.space_left);
        text.setText(AudioApplication.formatFree(this, free, sec));
    }
}
