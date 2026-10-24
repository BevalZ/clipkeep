package com.clipkeep.android;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class ImagePreviewsTest {
    private final List<File> files = new ArrayList<>();

    private File image(int width, int height, int color) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("preview-test-", ".png", context.getCacheDir()); files.add(file);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try (OutputStream output = new FileOutputStream(file)) {
            bitmap.eraseColor(color); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        return file;
    }
    @After public void tearDown() { for (File file : files) file.delete(); }

    @Test public void sampledPreviewRespectsEdgeLimitAndPreservesOriginal() throws Exception {
        File file = image(2049, 1537, Color.RED); byte[] original = Files.readAllBytes(file.toPath());
        Bitmap bitmap = Images.preview(file);
        try {
            assertTrue(bitmap.getWidth() <= 1024); assertTrue(bitmap.getHeight() <= 1024);
            assertEquals(Color.RED, bitmap.getPixel(0, 0));
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        } finally { bitmap.recycle(); }
    }
    @Test public void byteBudgetEvictsLeastRecentlyUsedWithoutRecyclingDisplayedBitmaps() throws Exception {
        ImagePreviews cache = new ImagePreviews(2 * 32 * 32 * 4); long token = cache.generation();
        File first = image(32, 32, Color.RED), second = image(32, 32, Color.GREEN), third = image(32, 32, Color.BLUE);
        Bitmap a = cache.load(first, token), b = cache.load(second, token);
        assertSame(a, cache.load(first, token));
        Bitmap c = cache.load(third, token);
        assertSame(a, cache.load(first, token)); assertSame(c, cache.load(third, token));
        assertNotSame(b, cache.load(second, token));
        assertFalse(b.isRecycled()); assertEquals(Color.GREEN, b.getPixel(0, 0));
        cache.clear(); assertFalse(a.isRecycled()); assertFalse(c.isRecycled());
    }
    @Test public void oversizePreviewIsDisplayedButNotRetained() throws Exception {
        ImagePreviews cache = new ImagePreviews(1024); long token = cache.generation();
        File file = image(32, 32, Color.BLUE);
        Bitmap first = cache.load(file, token), second = cache.load(file, token);
        assertNotSame(first, second); assertFalse(first.isRecycled());
        cache.clear();
    }
    @Test public void clearInvalidatesOldRequestsAndReleasesCachedPreview() throws Exception {
        ImagePreviews cache = new ImagePreviews(); File file = image(32, 32, Color.RED);
        long old = cache.generation(); Bitmap original = cache.load(file, old);
        cache.clear();
        assertFalse(cache.isCurrent(old)); assertNull(cache.load(file, old));
        assertNotSame(original, cache.load(file, cache.generation())); assertFalse(original.isRecycled());
        cache.clear();
    }
    @Test public void invalidFileIsNotCachedAndCanBeRetried() throws Exception {
        ImagePreviews cache = new ImagePreviews(); File file = image(32, 32, Color.RED);
        byte[] original = Files.readAllBytes(file.toPath()); Files.write(file.toPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> cache.load(file, cache.generation()));
        Files.write(file.toPath(), original);
        assertEquals(Color.RED, cache.load(file, cache.generation()).getPixel(0, 0)); cache.clear();
    }
}
