package com.github.axet.audiorecorder.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;

import java.io.File;
import java.util.Date;

public class Storage extends com.github.axet.audiolibrary.app.Storage {

    public Storage(Context context) {
        super(context);
    }

    public static String getFormatted(String format, Date date) {
        format = format.replaceAll("%s", SIMPLE.format(date));
        format = format.replaceAll("%I", ISO8601.format(date));
        format = format.replaceAll("%T", "" + System.currentTimeMillis() / 1000);
        return format;
    }

    public Uri getNewFile() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String ext = shared.getString(MainApplication.PREFERENCE_ENCODING, "");

        String format = "%s";

        format = shared.getString(MainApplication.PREFERENCE_FORMAT, format);

        format = getFormatted(format, new Date());

        Uri path = getStoragePath();
        String s = path.getScheme();

        if (Build.VERSION.SDK_INT >= 21 && s.startsWith(ContentResolver.SCHEME_CONTENT)) {
            Uri n = getNextFile(path, format, ext);
            return n;
        } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
            File f = getFile(path);
            if (!f.exists() && !f.mkdirs()) {
                throw new RuntimeException("Unable to create: " + path);
            }
            return Uri.fromFile(getNextFile(f, format, ext));
        } else {
            throw new RuntimeException("unknown uri");
        }
    }

}
