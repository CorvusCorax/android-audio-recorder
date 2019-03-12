package com.github.axet.audiorecorder.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Storage extends com.github.axet.audiolibrary.app.Storage {

    public static final SimpleDateFormat ISO8601Z = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US) {{
        setTimeZone(TimeZone.getTimeZone("UTC"));
    }};

    public static String getFormatted(String format, Date date) {
        format = format.replaceAll("%s", SIMPLE.format(date));
        format = format.replaceAll("%I", ISO8601.format(date));
        format = format.replaceAll("%T", "" + System.currentTimeMillis() / 1000);
        format = format.replaceAll("%U", ISO8601Z.format(date));
        return format;
    }

    public Storage(Context context) {
        super(context);
    }

    @Override
    public Uri getNewFile() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String ext = shared.getString(AudioApplication.PREFERENCE_ENCODING, "");

        String format = "%s";

        format = shared.getString(AudioApplication.PREFERENCE_FORMAT, format);

        format = getFormatted(format, new Date());

        Uri path = getStoragePath();
        String s = path.getScheme();

        if (Build.VERSION.SDK_INT >= 21 && s.equals(ContentResolver.SCHEME_CONTENT)) {
            return getNextFile(context, path, format, ext);
        } else if (s.equals(ContentResolver.SCHEME_FILE)) {
            File f = getFile(path);
            if (!f.exists() && !f.mkdirs() && !f.exists())
                throw new RuntimeException("Unable to create: " + path);
            return Uri.fromFile(getNextFile(f, format, ext));
        } else {
            throw new UnknownUri();
        }
    }

    public File getNewFile(File f, String ext) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        String format = "%s";

        format = shared.getString(AudioApplication.PREFERENCE_FORMAT, format);

        format = getFormatted(format, new Date());

        if (!f.exists() && !f.mkdirs() && !f.exists())
            throw new RuntimeException("Unable to create: " + f);
        return getNextFile(f, format, ext);
    }

}
