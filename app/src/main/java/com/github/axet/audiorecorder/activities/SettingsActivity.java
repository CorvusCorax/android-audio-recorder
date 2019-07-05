package com.github.axet.audiorecorder.activities;


import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.support.annotation.NonNull;
import android.support.v7.app.ActionBar;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.preference.PreferenceScreen;
import android.support.v7.preference.SwitchPreferenceCompat;
import android.view.MenuItem;

import com.github.axet.androidlibrary.widgets.AppCompatSettingsThemeActivity;
import com.github.axet.androidlibrary.widgets.NameFormatPreferenceCompat;
import com.github.axet.androidlibrary.widgets.SeekBarPreference;
import com.github.axet.androidlibrary.widgets.SilencePreferenceCompat;
import com.github.axet.androidlibrary.widgets.StoragePathPreferenceCompat;
import com.github.axet.androidlibrary.widgets.Toast;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.Factory;
import com.github.axet.audiolibrary.widgets.RecordingVolumePreference;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Storage;
import com.github.axet.audiorecorder.services.RecordingService;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends AppCompatSettingsThemeActivity implements PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {

    public static final int RESULT_STORAGE = 1;
    public static final int RESULT_CALL = 2;

    Storage storage;

    public static String[] PREMS = new String[]{Manifest.permission.READ_PHONE_STATE};

    @SuppressWarnings("unchecked")
    public static <T> T[] removeElement(Class<T> c, T[] aa, int i) {
        List<T> ll = Arrays.asList(aa);
        ll = new ArrayList<>(ll);
        ll.remove(i);
        return ll.toArray((T[]) Array.newInstance(c, ll.size()));
    }

    @Override
    public int getAppTheme() {
        return AudioApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark);
    }

    @Override
    public String getAppThemeKey() {
        return AudioApplication.PREFERENCE_THEME;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new Storage(this);
        setupActionBar();
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new GeneralPreferenceFragment()).commit();
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
//            actionBar.setBackgroundDrawable(new ColorDrawable(AudioApplication.getActionbarColor(this)));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        super.onSharedPreferenceChanged(sharedPreferences, key);
        if (key.equals(AudioApplication.PREFERENCE_CONTROLS)) {
            if (sharedPreferences.getBoolean(AudioApplication.PREFERENCE_CONTROLS, false))
                RecordingService.start(this);
            else
                RecordingService.stop(this);
        }
        if (key.equals(AudioApplication.PREFERENCE_STORAGE))
            storage.migrateLocalStorageDialog(this);
        if (key.equals(AudioApplication.PREFERENCE_RATE)) {
            int sampleRate = Integer.parseInt(sharedPreferences.getString(AudioApplication.PREFERENCE_RATE, ""));
            if (sampleRate != Sound.getValidRecordRate(Sound.getInMode(this), sampleRate))
                Toast.Error(this, "Not supported Hz");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        MainActivity.startActivity(this);
        finish();
    }

    @Override
    public boolean onPreferenceDisplayDialog(PreferenceFragmentCompat caller, Preference pref) {
        if (pref instanceof NameFormatPreferenceCompat) {
            NameFormatPreferenceCompat.show(caller, pref.getKey());
            return true;
        }
        if (pref instanceof SeekBarPreference) {
            RecordingVolumePreference.show(caller, pref.getKey());
            return true;
        }
        return false;
    }

    /**
     * This fragment shows general preferences only. It is used when the
     * activity is showing a two-pane settings UI.
     */
    public static class GeneralPreferenceFragment extends PreferenceFragmentCompat {
        public GeneralPreferenceFragment() {
        }

        void initPrefs(PreferenceManager pm, PreferenceScreen screen) {
            final Context context = screen.getContext();
            ListPreference enc = (ListPreference) pm.findPreference(AudioApplication.PREFERENCE_ENCODING);
            String v = enc.getValue();
            CharSequence[] ee = Factory.getEncodingTexts(context);
            CharSequence[] vv = Factory.getEncodingValues(context);
            if (ee.length > 1) {
                enc.setEntries(ee);
                enc.setEntryValues(vv);

                int i = enc.findIndexOfValue(v);
                if (i == -1) {
                    enc.setValueIndex(0);
                } else {
                    enc.setValueIndex(i);
                }

                bindPreferenceSummaryToValue(enc);
            } else {
                screen.removePreference(enc);
            }

            bindPreferenceSummaryToValue(pm.findPreference(AudioApplication.PREFERENCE_RATE));
            bindPreferenceSummaryToValue(pm.findPreference(AudioApplication.PREFERENCE_THEME));
            bindPreferenceSummaryToValue(pm.findPreference(AudioApplication.PREFERENCE_CHANNELS));
            bindPreferenceSummaryToValue(pm.findPreference(AudioApplication.PREFERENCE_FORMAT));
            bindPreferenceSummaryToValue(pm.findPreference(AudioApplication.PREFERENCE_VOLUME));

            StoragePathPreferenceCompat s = (StoragePathPreferenceCompat) pm.findPreference(AudioApplication.PREFERENCE_STORAGE);
            s.setStorage(new Storage(getContext()));
            s.setPermissionsDialog(this, Storage.PERMISSIONS_RW, RESULT_STORAGE);
            if (Build.VERSION.SDK_INT >= 21)
                s.setStorageAccessFramework(this, RESULT_STORAGE);

            AudioManager am = (AudioManager) context.getSystemService(AUDIO_SERVICE);
            Preference bluetooth = pm.findPreference(AudioApplication.PREFERENCE_SOURCE);
            if (!am.isBluetoothScoAvailableOffCall()) {
                bluetooth.setVisible(false);
            }
            bindPreferenceSummaryToValue(bluetooth);

            Preference p = pm.findPreference(AudioApplication.PREFERENCE_CALL);
            p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean b = (boolean) newValue;
                    if (b) {
                        if (!Storage.permitted(GeneralPreferenceFragment.this, PREMS, RESULT_CALL))
                            return false;
                    }
                    return true;
                }
            });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setHasOptionsMenu(true);
            addPreferencesFromResource(R.xml.pref_general);
            initPrefs(getPreferenceManager(), getPreferenceScreen());
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                getActivity().onBackPressed();
                return true;
            }
            return super.onOptionsItemSelected(item);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            StoragePathPreferenceCompat s = (StoragePathPreferenceCompat) findPreference(AudioApplication.PREFERENCE_STORAGE);
            switch (requestCode) {
                case RESULT_STORAGE:
                    s.onRequestPermissionsResult(permissions, grantResults);
                    break;
                case RESULT_CALL:
                    SwitchPreferenceCompat p = (SwitchPreferenceCompat) findPreference(AudioApplication.PREFERENCE_CALL);
                    if (!Storage.permitted(getContext(), PREMS))
                        p.setChecked(false);
                    else
                        p.setChecked(true);
                    break;
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            StoragePathPreferenceCompat s = (StoragePathPreferenceCompat) findPreference(AudioApplication.PREFERENCE_STORAGE);
            switch (requestCode) {
                case RESULT_STORAGE:
                    s.onActivityResult(resultCode, data);
                    break;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            SilencePreferenceCompat silent = (SilencePreferenceCompat) findPreference(AudioApplication.PREFERENCE_SILENT);
            silent.onResume();
        }
    }
}
