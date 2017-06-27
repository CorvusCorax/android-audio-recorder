package com.github.axet.audiorecorder.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Storage extends com.github.axet.audiolibrary.app.Storage {

    public static final String TMP_ENC = "encoding.data";

    public Storage(Context context) {
        super(context);
    }

    public Uri getNewFile() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String ext = shared.getString(MainApplication.PREFERENCE_ENCODING, "");

        String format = "%s";

        format = shared.getString(MainApplication.PREFERENCE_FORMAT, format);

        format = format.replaceAll("%s", SIMPLE.format(new Date()));
        format = format.replaceAll("%I", ISO8601.format(new Date()));
        format = format.replaceAll("%T", "" + System.currentTimeMillis() / 1000);

        Uri path = getStoragePath();
        String s = path.getScheme();

        if (Build.VERSION.SDK_INT >= 21 && s.startsWith(ContentResolver.SCHEME_CONTENT)) {
            Uri n = getNextFile(path, format, ext);
            String d = getDocumentName(n);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(path, DocumentsContract.getTreeDocumentId(path));
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(d);
            Uri childrenUri = DocumentsContract.createDocument(context.getContentResolver(), docUri, mime, d);
            return childrenUri;
        } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
            File f = new File(path.getPath());
            if (!f.exists() && !f.mkdirs()) {
                throw new RuntimeException("Unable to create: " + path);
            }
            return Uri.fromFile(getNextFile(f, format, ext));
        } else {
            throw new RuntimeException("unknown uri");
        }
    }


    public File getTempEncoding() {
        File internal = new File(context.getCacheDir(), TMP_ENC);
        if (internal.exists())
            return internal;

        // Starting in KITKAT, no permissions are required to read or write to the returned path;
        // it's always accessible to the calling app.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            if (!permitted(context, PERMISSIONS))
                return internal;
        }

        File c = context.getExternalCacheDir();
        if (c == null) // some old phones <15API with disabled sdcard return null
            return internal;

        File external = new File(c, TMP_ENC);

        if (external.exists())
            return external;

        long freeI = getFree(internal);
        long freeE = getFree(external);

        if (freeI > freeE)
            return internal;
        else
            return external;
    }
}
