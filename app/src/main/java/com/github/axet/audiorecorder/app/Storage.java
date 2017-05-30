package com.github.axet.audiorecorder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Storage extends com.github.axet.audiolibrary.app.Storage {

    public Storage(Context context) {
        super(context);
    }

    @Override
    public File getNewFile() {

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String ext = shared.getString(MainApplication.PREFERENCE_ENCODING, "");

        String format = "%s";

        format = shared.getString(MainApplication.PREFERENCE_FORMAT, format);

        format = format.replaceAll("%s", SIMPLE.format(new Date()));
        format = format.replaceAll("%I", ISO8601.format(new Date()));
        format = format.replaceAll("%T", "" + System.currentTimeMillis() / 1000);

        File parent = getStoragePath();
        if (!parent.exists()) {
            if (!parent.mkdirs())
                throw new RuntimeException("Unable to create: " + parent);
        }

        return getNextFile(parent, format, ext);
    }

}
