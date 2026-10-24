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
    ClipStore(Context context) { super(context, "history.db", null, 3); images = new File(context.getFilesDir(), "images"); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.execSQL("PRAGMA cache_size=-2048");
        db.execSQL("PRAGMA temp_store=FILE");
        // Android's connection pool owns WAL and statement-cache configuration.
        db.setMaxSqlCacheSize(8);
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // onOpen can run before onUpgrade on a legacy database; wait until kind exists.
        try (Cursor columns = db.rawQuery("PRAGMA table_info(clips)", null)) {
            boolean hasKind = false;
            while (columns.moveToNext()) if ("kind".equals(columns.getString(1))) { hasKind = true; break; }
            if (hasKind) deduplicate(db);
        }
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clips (_id INTEGER PRIMARY KEY, content TEXT NOT NULL, preview TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, kind INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX clips_order ON clips(pinned DESC, updated DESC, _id DESC)");
        db.execSQL("CREATE INDEX clips_loose ON clips(updated, _id) WHERE favorite=0 AND pinned=0");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) db.execSQL("ALTER TABLE clips ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
        if (oldVersion <= 2 && newVersion >= 3) deduplicate(db);
        if (oldVersion > 2 || newVersion != 3) throw new IllegalStateException("不支持的数据库版本");
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
            deduplicate(db);
            trim(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImages();
        return idForHash(hash);
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
            trim(db); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImages();
    }
    synchronized void delete(long id) { getWritableDatabase().delete("clips", "_id=?", new String[]{Long.toString(id)}); collectImages(); }
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
            deduplicate(db);
            trim(db); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        collectImages(); return idForHash(hash);
    }
    synchronized void collectImages() {
        // Iterate directory entries without retaining every filename in memory.
        if (!images.isDirectory()) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(images.toPath())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM clips WHERE kind=1 AND content=? LIMIT 1", new String[]{name})) {
                    if (!c.moveToFirst()) Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) { /* Retry during the next storage operation. */ }
    }

    /** Removes legacy/imported rows with equal kind and content, even if their hashes differ. */
    private void deduplicate(SQLiteDatabase db) {
        try (Cursor groups = db.rawQuery("SELECT kind,content FROM clips GROUP BY kind,content HAVING COUNT(*) > 1", null)) {
            while (groups.moveToNext()) {
                int kind = groups.getInt(0); String content = groups.getString(1);
                ArrayList<long[]> rows = new ArrayList<>();
                try (Cursor cursor = db.rawQuery("SELECT _id,favorite,pinned,updated FROM clips WHERE kind=? AND content=? ORDER BY updated DESC,_id DESC",
                        new String[]{Integer.toString(kind), content})) {
                    while (cursor.moveToNext()) rows.add(new long[]{cursor.getLong(0), cursor.getInt(1), cursor.getInt(2), cursor.getLong(3)});
                }
                if (rows.size() < 2) continue;
                long survivor = rows.get(0)[0], updated = rows.get(0)[3]; int favorite = 0, pinned = 0;
                for (long[] row : rows) { favorite = Math.max(favorite, (int)row[1]); pinned = Math.max(pinned, (int)row[2]); updated = Math.max(updated, row[3]); }
                ContentValues merged = new ContentValues(); merged.put("favorite", favorite); merged.put("pinned", pinned); merged.put("updated", updated);
                db.update("clips", merged, "_id=?", new String[]{Long.toString(survivor)});
                for (int i = 1; i < rows.size(); i++) db.delete("clips", "_id=?", new String[]{Long.toString(rows.get(i)[0])});
            }
        }
    }

    private static final class DatabaseUtilsCompat {
        static long longQuery(SQLiteDatabase db, String sql) {
            try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getLong(0); }
        }
    }
}
