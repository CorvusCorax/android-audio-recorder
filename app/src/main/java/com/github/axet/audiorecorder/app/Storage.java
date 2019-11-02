package com.github.axet.audiorecorder.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;

import com.github.axet.androidlibrary.services.StorageProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Storage extends com.github.axet.audiolibrary.app.Storage {

    public static final String SHARE = "share";

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

        String format = shared.getString(AudioApplication.PREFERENCE_FORMAT, "%s");

        format = getFormatted(format, new Date());

        Uri path = getStoragePath();
        String s = path.getScheme();

        if (Build.VERSION.SDK_INT >= 21 && s.equals(ContentResolver.SCHEME_CONTENT)) {
            return getNextFile(context, path, format, ext);
        } else if (s.equals(ContentResolver.SCHEME_FILE)) {
            File f = getFile(path);
            if (!Storage.mkdirs(f))
                throw new RuntimeException("unable to create: " + path);
            return Uri.fromFile(getNextFile(f, format, ext));
        } else {
            throw new UnknownUri();
        }
    }

    public File getIntentEncoding() {
        File internal = getFilesDir(context, SHARE);

        // Starting in KITKAT, no permissions are required to read or write to the returned path;
        // it's always accessible to the calling app.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            if (!permitted(context, PERMISSIONS_RW))
                return internal;
        }

        File external = context.getExternalFilesDir(SHARE);
        if (external == null) // some old phones <15API with disabled sdcard return null
            return internal;

        try {
            long freeI = getFree(internal);
            long freeE = getFree(external);
            if (freeI > freeE)
                return internal;
            else
                return external;
        } catch (RuntimeException e) { // samsung devices unable to determine external folders
            return internal;
        }
    }

    public Uri getNewIntentRecording() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String ext = shared.getString(AudioApplication.PREFERENCE_ENCODING, "");

        String format = shared.getString(AudioApplication.PREFERENCE_FORMAT, "%s");

        format = getFormatted(format, new Date());

        File f = getIntentEncoding();

        if (!Storage.mkdirs(f))
            throw new RuntimeException("unable to create: " + f);
        return Uri.fromFile(getNextFile(f, format, ext));
    }

    public void deleteTmp() {
        File internal = getFilesDir(context, SHARE);
        deleteTmp(internal);
        File external = context.getExternalFilesDir(SHARE);
        deleteTmp(external);
    }

    public void deleteTmp(File dir) {
        if (dir == null)
            return;
        long now = System.currentTimeMillis();
        File[] ff = dir.listFiles();
        if (ff == null)
            return;
        for (File f : ff) {
            if (f.isFile() && f.lastModified() + StorageProvider.TIMEOUT < now)
                Storage.delete(f);
        }
    }

    public File getNewFile(File f, String ext) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        String format = "%s";

        format = shared.getString(AudioApplication.PREFERENCE_FORMAT, format);

        format = getFormatted(format, new Date());

        if (!Storage.mkdirs(f))
            throw new RuntimeException("Unable to create: " + f);
        return getNextFile(f, format, ext);
    }

    @Override
    public void migrateLocalStorage() {
        super.migrateLocalStorage();
        deleteTmp();
    }
}
