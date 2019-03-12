package com.github.axet.audiorecorder.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.preference.PreferenceManager;
import android.support.v7.preference.ListPreference;
import android.util.AttributeSet;

import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Storage;

import java.util.Date;

public class NameFormatPreferenceCompat extends ListPreference {
    public long now = System.currentTimeMillis();

    public NameFormatPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public NameFormatPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NameFormatPreferenceCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NameFormatPreferenceCompat(Context context) {
        super(context);
    }

    @Override
    public boolean callChangeListener(Object newValue) {
        update(newValue);
        return super.callChangeListener(newValue);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        Object def = super.onGetDefaultValue(a, index);
        update(def);
        return def;
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        super.onSetInitialValue(restoreValue, defaultValue);
        CharSequence[] text = getEntries();
        CharSequence[] values = getEntryValues();
        for (int i = 0; i < values.length; i++)
            text[i] = getFormatted((String) values[i]);
        setEntries(text);
        update(getValue()); // defaultValue null after defaults set
    }

    public void update(Object value) {
        String v = (String) value;
        setSummary(getFormatted(v));
    }

    public String getFormatted(String str) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(getContext());
        String ext = shared.getString(AudioApplication.PREFERENCE_ENCODING, "");
        return Storage.getFormatted(str, new Date(now)) + "." + ext;
    }
}
