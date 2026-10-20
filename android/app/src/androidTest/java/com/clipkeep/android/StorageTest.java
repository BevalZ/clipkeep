package com.clipkeep.android;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class StorageTest {
    private Context getContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private ClipStore store;
    @Before public void setUp() throws Exception {
        getContext().deleteDatabase("history.db"); store = new ClipStore(getContext());
    }
    @After public void tearDown() throws Exception {
        store.close(); getContext().deleteDatabase("history.db");
    }
    @Test public void testDeduplicationProtectionAndUnicodeChunks() throws Exception {
        String text = String.join("", Collections.nCopies(30000, "中😀"));
        store.save(text); store.save(text);
        List<ClipStore.Row> rows = store.list("", false, 0);
        assertEquals(1, rows.size()); assertEquals(text, store.text(rows.get(0).id));
        assertEquals(400, rows.get(0).preview.codePointCount(0, rows.get(0).preview.length()));
        store.toggle(rows.get(0).id, true); store.clearHistory(); assertEquals(1, store.list("", false, 0).size());
        store.toggle(rows.get(0).id, true); store.clearHistory(); assertTrue(store.list("", false, 0).isEmpty());
    }
    @Test public void testPaginationSearchAndPairingExclusion() throws Exception {
        for (int i = 0; i < 190; i++) store.save("item " + i);
        List<ClipStore.Row> first = store.list("", false, 0), second = store.list("", false, ClipStore.PAGE_SIZE);
        assertEquals(81, first.size()); assertEquals(81, second.size()); assertEquals(first.get(80).id, second.get(0).id);
        store.save("literal % _ \\"); assertEquals(1, store.list("% _ \\", false, 0).size());
        try { store.save("cklan1://secret"); fail("pairing code stored"); } catch (IOException expected) { }
    }
    @Test public void testDeviceCryptoProvider() throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(); byte[] key = LanProtocol.newKey();
        LanProtocol.writeText(frame, key, "Android 设备加密测试");
        assertEquals("Android 设备加密测试", LanProtocol.readText(new ByteArrayInputStream(frame.toByteArray()), key));
    }
}
