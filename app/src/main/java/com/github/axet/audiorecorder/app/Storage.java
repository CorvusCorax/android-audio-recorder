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
            String ee = getExt(n);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(path, DocumentsContract.getTreeDocumentId(path));
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ee);
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

}
