package com.clipkeep.android;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;

final class Transfer {
    static final class Control {
        private volatile boolean cancelled;
        private Socket socket;
        synchronized void cancel() { cancelled = true; if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
        synchronized void attach(Socket value) throws IOException { check(); socket = value; }
        synchronized void detach() { socket = null; }
        void check() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("发送已取消；若已完成上传，请检查对方历史"); }
    }
    private static final ThreadLocal<Control> CONTROL = new ThreadLocal<>();
    private static void check() throws IOException { Control control = CONTROL.get(); if (control != null) control.check(); }

    interface Write { void write(OutputStream stream) throws Exception; }
    interface Read { void read(InputStream stream) throws Exception; }
    static void send(LanProtocol.Peer peer, Write write) throws Exception {
        exchange(peer, write, in -> LanProtocol.verifyAck(in, peer.key));
    }
    static void probe(LanProtocol.Peer peer) throws Exception {
        probe(peer.socketAddress(), peer.key);
    }
    static void probe(InetSocketAddress target, byte[] key) throws Exception {
        byte[] challenge = LanProtocol.newKey();
        exchange(target, out -> LanProtocol.writePayload(out, key, 0x434b4831, challenge), in -> {
            LanProtocol.Message response = LanProtocol.readMessage(in, key);
            if (!response.probeReply() || !MessageDigest.isEqual(challenge, response.bytes)) throw new IOException("连接测试响应无效");
        });
    }
    static boolean supportsChunked(LanProtocol.Peer peer) throws IOException {
        check();
        try {
            byte[] challenge = LanProtocol.newKey();
            final boolean[] supported = {false};
            exchange(peer.socketAddress(), out -> LanProtocol.writePayload(out, peer.key, 0x434b4832, challenge), in -> {
                LanProtocol.Message response = LanProtocol.readMessage(in, peer.key);
                supported[0] = response.capabilityReply() && response.bytes.length == 33 && java.security.MessageDigest.isEqual(challenge, java.util.Arrays.copyOf(response.bytes, 32)) && response.bytes[32] == 1;
            });
            return supported[0];
        } catch (Exception ignored) { check(); return false; }
    }
    static void sendChunked(LanProtocol.Peer peer, byte[] kind, byte[] bytes) throws Exception {
        byte[] id = java.util.Arrays.copyOf(LanProtocol.newKey(), 16);
        long total = (bytes.length + LanProtocol.CHUNK_BYTES - 1L) / LanProtocol.CHUNK_BYTES;
        exchange(peer, out -> {
            for (long index = 0; index < total; index++) {
                check();
                int start = (int)(index * LanProtocol.CHUNK_BYTES), end = Math.min(bytes.length, start + LanProtocol.CHUNK_BYTES);
                LanProtocol.writeChunk(out, peer.key, id, kind, index, total, bytes.length, java.util.Arrays.copyOfRange(bytes, start, end));
            }
        }, in -> {
            LanProtocol.Message receipt = LanProtocol.readMessage(in, peer.key);
            if (!receipt.chunkReceipt() || !MessageDigest.isEqual(id, receipt.bytes)) throw new IOException("分块确认与本次传输不匹配");
        });
    }
    private static void exchange(LanProtocol.Peer peer, Write write, Read read) throws Exception {
        check(); exchange(peer.socketAddress(), write, read);
    }
    static void exchange(InetSocketAddress target, Write write, Read read) throws Exception {
        // Retry only failed connects. Once any payload can have been sent, never replay it.
        for (int attempt = 0; attempt < 3; attempt++) {
            check(); Control control = CONTROL.get();
            try (Socket socket = new Socket()) {
                if (control != null) control.attach(socket);
                try { socket.connect(target, 2500); }
                catch (IOException error) {
                    check();
                    if (attempt == 2) throw new IOException("无法连接设备：请检查接收开关、VPN 和防火墙", error);
                    Thread.sleep(attempt == 0 ? 350 : 1000); continue;
                }
                ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
                deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 12, TimeUnit.SECONDS);
                try {
                    socket.setSoTimeout(8000);
                    write.write(socket.getOutputStream()); read.read(socket.getInputStream()); return;
                } finally { deadline.shutdownNow(); }
            } finally { if (control != null) control.detach(); }
        }
    }
    static void clip(ClipStore store, long id, LanProtocol.Peer peer, Control control) throws Exception {
        CONTROL.set(control);
        try { check(); clip(store, id, peer); }
        catch (Exception error) { control.check(); throw error; }
        finally { CONTROL.remove(); }
    }
    static void clip(ClipStore store, long id, LanProtocol.Peer peer) throws Exception {
        boolean image = store.isImage(id);
        if (image) {
            byte[] bytes = store.imageBytes(id);
            if (bytes.length > LanProtocol.CHUNK_BYTES && supportsChunked(peer)) sendChunked(peer, new byte[]{'C','K','I','1'}, bytes);
            else send(peer, out -> LanProtocol.writePayload(out, peer.key, 0x434b4931, bytes));
        } else {
            String text = store.text(id);
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > LanProtocol.CHUNK_BYTES && supportsChunked(peer)) sendChunked(peer, new byte[]{'C','K','L','1'}, bytes);
            else send(peer, out -> LanProtocol.writeText(out, peer.key, text));
        }
    }
}
