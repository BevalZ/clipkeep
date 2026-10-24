package com.clipkeep.android;

import android.graphics.Bitmap;
import android.util.LruCache;
import java.io.*;

/** Foreground-only previews of immutable, content-addressed image files. */
final class ImagePreviews {
    private final LruCache<String, Bitmap> cache;
    private long generation;

    ImagePreviews() { this(4 * 1024 * 1024); }
    ImagePreviews(int bytes) {
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
        };
    }
    synchronized long generation() { return generation; }
    synchronized boolean isCurrent(long expected) { return generation == expected; }
    synchronized void clear() { ++generation; cache.evictAll(); }

    Bitmap load(File file, long expected) throws IOException {
        String key = file.getAbsolutePath();
        synchronized (this) {
            if (!isCurrent(expected)) return null;
            Bitmap cached = cache.get(key);
            if (cached != null) return cached;
        }
        // Decode off the UI thread without holding the lock needed by clear().
        Bitmap bitmap = Images.preview(file);
        synchronized (this) {
            if (!isCurrent(expected)) { bitmap.recycle(); return null; }
            if (bitmap.getAllocationByteCount() <= cache.maxSize()) cache.put(key, bitmap);
        }
        // Eviction only drops references: an ImageView may still display this bitmap.
        return bitmap;
    }
}
