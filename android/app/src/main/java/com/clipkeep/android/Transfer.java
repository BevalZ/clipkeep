package com.clipkeep.android;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;

final class Transfer {
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
    private static void exchange(LanProtocol.Peer peer, Write write, Read read) throws Exception {
        exchange(peer.socketAddress(), write, read);
    }
    static void exchange(InetSocketAddress target, Write write, Read read) throws Exception {
        // Retry only failed connects. Once any payload can have been sent, never replay it.
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Socket socket = new Socket()) {
                try { socket.connect(target, 2500); }
                catch (IOException error) {
                    if (attempt == 2) throw new IOException("无法连接设备：请检查接收开关、VPN 和防火墙", error);
                    Thread.sleep(attempt == 0 ? 350 : 1000); continue;
                }
                ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
                deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 12, TimeUnit.SECONDS);
                try {
                    socket.setSoTimeout(8000);
                    write.write(socket.getOutputStream()); read.read(socket.getInputStream()); return;
                } finally { deadline.shutdownNow(); }
            }
        }
    }
    static void clip(ClipStore store, long id, LanProtocol.Peer peer) throws Exception {
        boolean image = store.isImage(id);
        if (image) {
            byte[] bytes = store.imageBytes(id);
            send(peer, out -> LanProtocol.writePayload(out, peer.key, 0x434b4931, bytes));
        } else {
            String text = store.text(id);
            send(peer, out -> LanProtocol.writeText(out, peer.key, text));
        }
    }
}
