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
    static Uri uri(long id) { return Uri.parse("content://" + AUTHORITY + "/" + id); }
    private File file(Uri uri) throws FileNotFoundException {
        try {
            if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) throw new IOException();
            return ((ClipkeepApp)getContext().getApplicationContext()).store.imageFile(Long.parseLong(uri.getLastPathSegment()));
        } catch (Exception error) { throw new FileNotFoundException("图片已删除"); }
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
