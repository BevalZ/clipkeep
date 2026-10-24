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
    private byte[] png() {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle();
        return out.toByteArray();
    }
    @Test public void testImagesProviderAndProtection() throws Exception {
        byte[] png = png(); long id = store.saveImage(png);
        assertEquals(id, store.saveImage(png)); assertTrue(store.isImage(id));
        assertEquals(1, store.list("", false, 0).size()); assertTrue(store.list("", false, 0).get(0).image);
        assertArrayEquals(png, store.imageBytes(id));
        assertThrows(IOException.class, () -> store.text(id));
        store.toggle(id, true); store.clearHistory(); assertTrue(store.imageFile(id).exists());
        File file = store.imageFile(id); store.delete(id); assertFalse(file.exists());
        assertThrows(Exception.class, () -> store.saveImage(new byte[]{1,2,3}));
    }
    @Test public void testMigrationKeepsTextAndFlags() throws Exception {
        store.close(); getContext().deleteDatabase("history.db");
        android.database.sqlite.SQLiteDatabase db = getContext().openOrCreateDatabase("history.db", 0, null);
        db.execSQL("CREATE TABLE clips (_id INTEGER PRIMARY KEY, content TEXT NOT NULL, preview TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)");
        db.execSQL("INSERT INTO clips VALUES(1,'旧文本','旧文本','legacy',9,1,1,1)"); db.setVersion(1); db.close();
        store = new ClipStore(getContext()); assertEquals("旧文本", store.text(1));
        assertFalse(store.isImage(1)); assertTrue(store.list("", true, 0).get(0).pinned);
        assertTrue(store.isImage(store.saveImage(png())));
    }
    @Test public void testDiscoveryNonceAndNoKeyLeak() throws Exception {
        java.net.DatagramSocket server = new java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder = new Thread(() -> Discovery.respond(server, "test-device")); responder.start();
        try (java.net.DatagramSocket client = new java.net.DatagramSocket()) {
            client.setSoTimeout(1500);
            byte[] query = "CKD1? 0123456789abcdef0123456789abcdef".getBytes();
            client.send(new java.net.DatagramPacket(query, query.length, java.net.InetAddress.getByName("127.0.0.1"), server.getLocalPort()));
            byte[] bytes = new byte[1024]; java.net.DatagramPacket reply = new java.net.DatagramPacket(bytes, bytes.length); client.receive(reply);
            org.json.JSONObject json = new org.json.JSONObject(new String(bytes,0,reply.getLength()));
            assertEquals("0123456789abcdef0123456789abcdef", json.getString("nonce"));
            assertFalse(json.has("key")); assertFalse(json.has("code"));
        } finally { server.close(); responder.join(2000); }
    }
    @Test public void testReceiverImageAckAndRejectInvalidImage() throws Exception {
        byte[] key = LanProtocol.newKey();
        try (LanReceiver receiver = new LanReceiver(getContext(), key, store, new LanReceiver.Listener() {
            public void received(long id) { }
            public void failed(Exception error) { }
        })) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", LanProtocol.PORT)) {
                socket.setSoTimeout(3000);
                LanProtocol.writePayload(socket.getOutputStream(), key, 0x434b4931, png());
                LanProtocol.verifyAck(socket.getInputStream(), key);
            }
            assertEquals(1, store.list("", false, 0).size());
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", LanProtocol.PORT)) {
                socket.setSoTimeout(3000);
                LanProtocol.writePayload(socket.getOutputStream(), key, 0x434b4931, new byte[]{1,2,3});
                assertThrows(IOException.class, () -> LanProtocol.verifyAck(socket.getInputStream(), key));
            }
            assertEquals(1, store.list("", false, 0).size());
        }
    }
    @Test public void testAuthenticatedProbeNeverChangesHistoryOrPairing() throws Exception {
        byte[] key = LanProtocol.newKey(); long id = store.save("keep existing");
        String peers = LanSettings.peers(getContext()).toString();
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        try (LanReceiver receiver = new LanReceiver(getContext(), key, store, new LanReceiver.Listener() {
            public void received(long saved) { changes.incrementAndGet(); }
            public void failed(Exception error) { }
        })) {
            Transfer.probe(LanProtocol.parseCode("cklan1://127.0.0.1:45788/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key)));
            assertEquals(0, changes.get()); assertEquals(1, store.list("", false, 0).size());
            assertEquals(id, store.list("", false, 0).get(0).id);
            assertEquals(peers, LanSettings.peers(getContext()).toString());
        }
    }
    @Test public void testDeviceCryptoProvider() throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(); byte[] key = LanProtocol.newKey();
        LanProtocol.writeText(frame, key, "Android 设备加密测试");
        assertEquals("Android 设备加密测试", LanProtocol.readText(new ByteArrayInputStream(frame.toByteArray()), key));
    }
    @Test public void testCapabilityNegotiationAndChunkedTextTransfer() throws Exception {
        byte[] key = LanProtocol.newKey();
        LanProtocol.Peer peer = LanProtocol.parseCode("cklan1://127.0.0.1:45788/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        byte[] text = new byte[700000]; for (int i = 0; i < text.length; i++) text[i] = (byte)('a' + (i % 23));
        try (LanReceiver receiver = new LanReceiver(getContext(), key, store, new LanReceiver.Listener() { public void received(long id) { } public void failed(Exception error) { } })) {
            assertTrue(Transfer.supportsChunked(peer));
            Transfer.sendChunked(peer, new byte[]{'C','K','L','1'}, text);
            assertEquals(new String(text, java.nio.charset.StandardCharsets.UTF_8), store.text(store.list("", false, 0).get(0).id));
        }
    }    @Test public void testPartialChunksAndMalformedUtf8NeverCommit() throws Exception {
        byte[] key = LanProtocol.newKey(), id = new byte[16];
        try (LanReceiver receiver = new LanReceiver(getContext(), key, store, new LanReceiver.Listener() { public void received(long saved) { } public void failed(Exception error) { } })) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", LanProtocol.PORT)) {
                LanProtocol.writeChunk(socket.getOutputStream(), key, id, new byte[]{'C','K','L','1'}, 0, 2, LanProtocol.CHUNK_BYTES + 1, new byte[LanProtocol.CHUNK_BYTES]);
                socket.shutdownOutput(); socket.setSoTimeout(3000);
                assertEquals(-1, socket.getInputStream().read());
            }
            assertTrue(store.list("", false, 0).isEmpty());
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", LanProtocol.PORT)) {
                LanProtocol.writeChunk(socket.getOutputStream(), key, id, new byte[]{'C','K','L','1'}, 0, 1, 1, new byte[]{(byte)0xff});
                socket.setSoTimeout(3000); assertEquals(-1, socket.getInputStream().read());
            }
            assertTrue(store.list("", false, 0).isEmpty());
        }
    }
    @Test public void testCancelClosesPendingAckWithoutRetry() throws Exception {
        byte[] key = LanProtocol.newKey();
        LanProtocol.Peer peer = LanProtocol.parseCode("cklan1://127.0.0.1:45788/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        long clip = store.save("cancel pending ACK");
        Transfer.Control control = new Transfer.Control();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.net.ServerSocket server = new java.net.ServerSocket(LanProtocol.PORT)) {
            server.setSoTimeout(3000);
            java.util.concurrent.Future<?> send = worker.submit(() -> {
                try { Transfer.clip(store, clip, peer, control); fail("cancelled send succeeded"); }
                catch (Exception error) { assertTrue(error.getMessage().contains("取消")); }
            });
            try (java.net.Socket socket = server.accept()) {
                socket.setSoTimeout(3000); assertEquals("cancel pending ACK", LanProtocol.readText(socket.getInputStream(), key));
                control.cancel(); send.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(-1, socket.getInputStream().read());
                server.setSoTimeout(500); assertThrows(java.net.SocketTimeoutException.class, server::accept);
            }
        } finally { worker.shutdownNow(); }
    }

}
