package com.github.axet.audiorecorder.widgets;

import android.content.Context;
import android.util.AttributeSet;

import com.github.axet.audiorecorder.app.Storage;

import java.util.Date;

public class NameFormatPreferenceCompat extends com.github.axet.androidlibrary.widgets.NameFormatPreferenceCompat {
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
    public String getFormatted(String str) {
        CharSequence[] text = getEntries();
        CharSequence[] values = getEntryValues();
        for (int i = 0; i < text.length; i++) {
            String t = text[i].toString();
            String v = values[i].toString();
            if (v.equals(str))
                return t;
        }
        return Storage.getFormatted(str, new Date(1487926249000l));
    }
}
