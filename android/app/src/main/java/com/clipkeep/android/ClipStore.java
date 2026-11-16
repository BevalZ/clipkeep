package com.clipkeep.android;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class ClipStore extends SQLiteOpenHelper {
    static final int PAGE_SIZE = 80;
    static final long DEFAULT_QUOTA = 50L * 1024 * 1024;
    static final class Row {
        final long id, updated, bytes;
        final String preview;
        final boolean favorite, pinned, image;
        Row(Cursor c) {
            id = c.getLong(0); preview = c.getString(1); updated = c.getLong(2);
            bytes = c.getLong(3); favorite = c.getInt(4) != 0; pinned = c.getInt(5) != 0; image = c.getInt(6) != 0;
        }
    }
    private final File images;
    private boolean imagesDirty = true;
    ClipStore(Context context) { super(context, "history.db", null, 3); images = new File(context.getFilesDir(), "images"); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.execSQL("PRAGMA cache_size=-2048");
        db.execSQL("PRAGMA temp_store=FILE");
        // Android's connection pool owns WAL and statement-cache configuration.
        db.setMaxSqlCacheSize(8);
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Additive indexes also accelerate existing installations without changing row identities.
        db.execSQL("CREATE INDEX IF NOT EXISTS clips_image_file ON clips(content) WHERE kind=1");
        db.execSQL("CREATE INDEX IF NOT EXISTS clips_loose_bytes ON clips(updated,_id,bytes) WHERE favorite=0 AND pinned=0");
        db.execSQL("CREATE INDEX IF NOT EXISTS clips_favorites_order ON clips(pinned DESC,updated DESC,_id DESC) WHERE favorite=1");
        deduplicate(db);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clips (_id INTEGER PRIMARY KEY, content TEXT NOT NULL, preview TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, kind INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX clips_order ON clips(pinned DESC, updated DESC, _id DESC)");
        db.execSQL("CREATE INDEX clips_loose ON clips(updated, _id) WHERE favorite=0 AND pinned=0");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 2 || newVersion != 3) throw new IllegalStateException("不支持的数据库版本");
        if (oldVersion == 1) db.execSQL("ALTER TABLE clips ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
        // onOpen repairs duplicate rows after all schema changes have committed.
    }
    synchronized long save(String text) throws IOException, GeneralSecurityException {
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
            long survivor = mergeEqual(db, idForHash(hash));
            ContentValues canonical = new ContentValues(); canonical.put("hash", hash);
            db.update("clips", canonical, "_id=?", new String[]{Long.toString(survivor)});
            imagesDirty |= trim(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImagesIfNeeded();
        return idForHash(hash);
    }
    private boolean trim(SQLiteDatabase db) {
        boolean removedImages = false;
        long total = DatabaseUtilsCompat.longQuery(db, "SELECT COALESCE(SUM(bytes),0) FROM clips WHERE favorite=0 AND pinned=0");
        // Bounded ID batches, never full text bodies.
        while (total > DEFAULT_QUOTA) {
            ArrayList<long[]> batch = new ArrayList<>(64);
            try (Cursor c = db.rawQuery("SELECT _id,bytes,kind FROM clips WHERE favorite=0 AND pinned=0 ORDER BY updated,_id LIMIT 64", null)) {
                while (c.moveToNext() && total > DEFAULT_QUOTA) {
                    batch.add(new long[]{c.getLong(0), c.getLong(1)}); total -= c.getLong(1);
                    removedImages |= c.getInt(2) == 1;
                }
            }
            if (batch.isEmpty()) break;
            for (long[] row : batch) db.delete("clips", "_id=?", new String[]{Long.toString(row[0])});
        }
        return removedImages;
    }
    synchronized List<Row> list(String query, boolean favorites, int offset) {
        String where = favorites ? "favorite=1" : "1=1";
        String[] args = null;
        if (!query.isEmpty()) {
            where += " AND content LIKE ? ESCAPE '\\'";
            args = new String[]{"%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"};
        }
        ArrayList<Row> rows = new ArrayList<>(PAGE_SIZE + 1);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,preview,updated,bytes,favorite,pinned,kind FROM clips WHERE " + where
            + " ORDER BY pinned DESC,updated DESC,_id DESC LIMIT " + (PAGE_SIZE + 1) + " OFFSET " + Math.max(0, offset), args)) {
            while (c.moveToNext()) rows.add(new Row(c));
        }
        return rows;
    }
    synchronized String text(long id) throws IOException {
        if (isImage(id)) throw new IOException("此条目为图片");
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
            imagesDirty |= trim(db); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImagesIfNeeded();
    }
    synchronized void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT kind FROM clips WHERE _id=?", new String[]{Long.toString(id)})) {
            if (c.moveToFirst()) imagesDirty |= c.getInt(0) == 1;
        }
        db.delete("clips", "_id=?", new String[]{Long.toString(id)});
        collectImagesIfNeeded();
    }
    synchronized void clearHistory() { getWritableDatabase().delete("clips", "favorite=0 AND pinned=0", null); collectImages(); }

    private long idForHash(String hash) throws IOException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id FROM clips WHERE hash=?", new String[]{hash})) {
            if (!c.moveToFirst()) throw new IOException("保存后未找到记录");
            return c.getLong(0);
        }
    }
    synchronized boolean isImage(long id) throws IOException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT kind FROM clips WHERE _id=?", new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) throw new IOException("条目已删除"); return c.getInt(0) == 1;
        }
    }
    synchronized File imageFile(long id) throws IOException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT content FROM clips WHERE _id=? AND kind=1", new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) throw new IOException("图片已删除");
            String name = c.getString(0);
            if (!name.matches("[a-f0-9]{64}\\.png")) throw new IOException("图片路径无效");
            File file = new File(images, name);
            if (!file.isFile()) throw new IOException("图片文件不存在");
            return file;
        }
    }
    synchronized byte[] imageBytes(long id) throws IOException {
        try (InputStream input = new FileInputStream(imageFile(id))) { return Images.read(input); }
    }
    synchronized long saveImage(byte[] png) throws Exception {
        try {
            return saveImageInternal(png);
        } catch (Exception error) {
            // A failed DB commit can leave a fully written file. Retry cleanup on the next operation.
            imagesDirty = true;
            throw error;
        }
    }
    private long saveImageInternal(byte[] png) throws Exception {
        Images.validate(png);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
        StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        String hash = "image:" + hex, name = hex + ".png";
        if (!images.isDirectory() && !images.mkdirs()) throw new IOException("无法创建图片目录");
        File destination = new File(images, name);
        if (!destination.exists()) {
            File temporary = File.createTempFile("import-", ".tmp", images);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(png); output.getFD().sync(); }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
        }
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("content", name); values.put("preview", "[图片]");
            values.put("hash", hash); values.put("bytes", png.length); values.put("kind", 1); values.put("updated", System.currentTimeMillis());
            if (db.insertWithOnConflict("clips", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                ContentValues stamp = new ContentValues(); stamp.put("updated", System.currentTimeMillis());
                db.update("clips", stamp, "hash=?", new String[]{hash});
            }
            long survivor = mergeEqual(db, idForHash(hash));
            ContentValues canonical = new ContentValues(); canonical.put("hash", hash);
            db.update("clips", canonical, "_id=?", new String[]{Long.toString(survivor)});
            imagesDirty |= trim(db); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImagesIfNeeded(); return idForHash(hash);
    }
    private void collectImagesIfNeeded() { if (imagesDirty) collectImages(); }
    synchronized void collectImages() {
        // Iterate directory entries without retaining every filename in memory.
        imagesDirty = true;
        if (!images.isDirectory()) {
            if (Files.notExists(images.toPath())) imagesDirty = false;
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(images.toPath())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM clips WHERE kind=1 AND content=? LIMIT 1", new String[]{name})) {
                    if (!c.moveToFirst()) Files.deleteIfExists(file);
                }
            }
            imagesDirty = false;
        } catch (IOException ignored) { imagesDirty = true; /* Retry during the next storage operation. */ }
    }

    /** Compare content in SQLite; never materialize large duplicate texts in CursorWindow. */
    private long mergeEqual(SQLiteDatabase db, long representative) {
        String equal = "kind=(SELECT kind FROM clips WHERE _id=?) AND content=(SELECT content FROM clips WHERE _id=?)";
        long survivor;
        try (Cursor cursor = db.rawQuery("SELECT _id FROM clips WHERE " + equal + " ORDER BY updated DESC,_id DESC LIMIT 2",
                new String[]{Long.toString(representative), Long.toString(representative)})) {
            if (!cursor.moveToFirst()) throw new SQLiteException("未找到去重记录");
            survivor = cursor.getLong(0);
            if (!cursor.moveToNext()) return survivor;
        }
        db.execSQL("UPDATE clips SET favorite=(SELECT MAX(favorite) FROM clips WHERE " + equal + "),"
            + "pinned=(SELECT MAX(pinned) FROM clips WHERE " + equal + "),"
            + "updated=(SELECT MAX(updated) FROM clips WHERE " + equal + ") WHERE _id=?",
            new Object[]{representative, representative, representative, representative, representative, representative, survivor});
        // Use the surviving row as the reference so it remains valid throughout deletion.
        db.execSQL("DELETE FROM clips WHERE " + equal + " AND _id<>?", new Object[]{survivor, survivor, survivor});
        return survivor;
    }
    /** Full repair runs on open only; each save checks just the matching incoming content. */
    private void deduplicate(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            while (true) {
                ArrayList<Long> ids = new ArrayList<>(64);
                try (Cursor cursor = db.rawQuery("SELECT MIN(_id) FROM clips GROUP BY kind,content HAVING COUNT(*)>1 LIMIT 64", null)) {
                    while (cursor.moveToNext()) ids.add(cursor.getLong(0));
                }
                if (ids.isEmpty()) break;
                for (long id : ids) mergeEqual(db, id);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static final class DatabaseUtilsCompat {
        static long longQuery(SQLiteDatabase db, String sql) {
            try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getLong(0); }
        }
    }
}
