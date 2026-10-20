package com.clipkeep.android;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class ClipStore extends SQLiteOpenHelper {
    static final int PAGE_SIZE = 80;
    static final long DEFAULT_QUOTA = 50L * 1024 * 1024;
    static final class Row {
        final long id, updated, bytes;
        final String preview;
        final boolean favorite, pinned;
        Row(Cursor c) {
            id = c.getLong(0); preview = c.getString(1); updated = c.getLong(2);
            bytes = c.getLong(3); favorite = c.getInt(4) != 0; pinned = c.getInt(5) != 0;
        }
    }
    ClipStore(Context context) { super(context, "history.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.execSQL("PRAGMA cache_size=-2048");
        db.execSQL("PRAGMA temp_store=FILE");
        // Android's connection pool owns WAL and statement-cache configuration.
        db.setMaxSqlCacheSize(8);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clips (_id INTEGER PRIMARY KEY, content TEXT NOT NULL, preview TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX clips_order ON clips(pinned DESC, updated DESC, _id DESC)");
        db.execSQL("CREATE INDEX clips_loose ON clips(updated, _id) WHERE favorite=0 AND pinned=0");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("不支持的数据库版本");
    }
    synchronized void save(String text) throws IOException, GeneralSecurityException {
        if (text == null || text.trim().isEmpty()) throw new IOException("没有可保存的文本");
        if (text.contains(LanProtocol.PREFIX)) throw new IOException("配对码包含密钥，不会保存到历史");
        if (text.length() > LanProtocol.MAX_TEXT_BYTES) throw new IOException("文本超过 2 MB");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LanProtocol.MAX_TEXT_BYTES) throw new IOException("文本超过 2 MB");
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        int end = text.offsetByCodePoints(0, Math.min(400, text.codePointCount(0, text.length())));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("content", text); values.put("preview", text.substring(0, end));
            values.put("hash", hash); values.put("bytes", bytes.length); values.put("updated", System.currentTimeMillis());
            if (db.insertWithOnConflict("clips", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                ContentValues stamp = new ContentValues(); stamp.put("updated", System.currentTimeMillis());
                db.update("clips", stamp, "hash=?", new String[]{hash});
            }
            trim(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void trim(SQLiteDatabase db) {
        long total = DatabaseUtilsCompat.longQuery(db, "SELECT COALESCE(SUM(bytes),0) FROM clips WHERE favorite=0 AND pinned=0");
        // Bounded ID batches, never full text bodies.
        while (total > DEFAULT_QUOTA) {
            ArrayList<long[]> batch = new ArrayList<>(64);
            try (Cursor c = db.rawQuery("SELECT _id,bytes FROM clips WHERE favorite=0 AND pinned=0 ORDER BY updated,_id LIMIT 64", null)) {
                while (c.moveToNext() && total > DEFAULT_QUOTA) {
                    batch.add(new long[]{c.getLong(0), c.getLong(1)}); total -= c.getLong(1);
                }
            }
            if (batch.isEmpty()) break;
            for (long[] row : batch) db.delete("clips", "_id=?", new String[]{Long.toString(row[0])});
        }
    }
    synchronized List<Row> list(String query, boolean favorites, int offset) {
        String where = favorites ? "favorite=1" : "1=1";
        String[] args = null;
        if (!query.isEmpty()) {
            where += " AND content LIKE ? ESCAPE '\\'";
            args = new String[]{"%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"};
        }
        ArrayList<Row> rows = new ArrayList<>(PAGE_SIZE + 1);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,preview,updated,bytes,favorite,pinned FROM clips WHERE " + where
            + " ORDER BY pinned DESC,updated DESC,_id DESC LIMIT " + (PAGE_SIZE + 1) + " OFFSET " + Math.max(0, offset), args)) {
            while (c.moveToNext()) rows.add(new Row(c));
        }
        return rows;
    }
    synchronized String text(long id) throws IOException {
        // Keep every CursorWindow small even for the maximum 2 MB text.
        StringBuilder result = new StringBuilder();
        int start = 1;
        while (true) {
            String part;
            try (Cursor c = getReadableDatabase().rawQuery("SELECT substr(content,?,16384) FROM clips WHERE _id=?",
                new String[]{Integer.toString(start), Long.toString(id)})) {
                if (!c.moveToFirst()) throw new IOException("条目已删除");
                part = c.getString(0);
            }
            result.append(part);
            int count = part.codePointCount(0, part.length());
            if (count < 16384) return result.toString();
            start += count;
        }
    }
    synchronized void toggle(long id, boolean favorite) {
        String column = favorite ? "favorite" : "pinned";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE clips SET " + column + "=1-" + column + " WHERE _id=?", new Object[]{id});
            trim(db); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void delete(long id) { getWritableDatabase().delete("clips", "_id=?", new String[]{Long.toString(id)}); }
    synchronized void clearHistory() { getWritableDatabase().delete("clips", "favorite=0 AND pinned=0", null); }

    private static final class DatabaseUtilsCompat {
        static long longQuery(SQLiteDatabase db, String sql) {
            try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getLong(0); }
        }
    }
}
