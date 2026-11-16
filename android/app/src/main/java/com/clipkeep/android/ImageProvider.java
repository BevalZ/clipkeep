package com.clipkeep.android;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only content URIs with per-recipient grants, no broad filesystem exposure. */
public final class ImageProvider extends ContentProvider {
    static final String AUTHORITY = "com.clipkeep.android.images";
    static Uri uri(ClipStore store, long id) throws IOException {
        return Uri.parse("content://" + AUTHORITY + "/" + id + "/" + store.imageFile(id).getName());
    }
    static File resolve(ClipStore store, Uri uri) throws FileNotFoundException {
        try {
            // A row ID can be reused after deletion. Bind grants to immutable image content too.
            // Legacy ID-only URIs must fail rather than grant access to a replacement image.
            if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 2) throw new IOException();
            File file = store.imageFile(Long.parseLong(uri.getPathSegments().get(0)));
            if (!file.getName().equals(uri.getLastPathSegment())) throw new IOException();
            return file;
        } catch (Exception error) { throw new FileNotFoundException("图片已删除"); }
    }
    private File file(Uri uri) throws FileNotFoundException {
        return resolve(((ClipkeepApp)getContext().getApplicationContext()).store, uri);
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只读图片");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        try {
            File file = file(uri); Object[] row = new Object[columns.length];
            for (int i=0; i<columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "clipkeep.png";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
        } catch (FileNotFoundException ignored) { }
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
