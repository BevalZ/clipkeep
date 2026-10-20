package com.clipkeep.android;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

final class Transfer {
    interface Write { void write(OutputStream stream) throws Exception; }
    static void send(LanProtocol.Peer peer, Write write) throws Exception {
        try (Socket socket = new Socket()) {
            ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
            deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 15, TimeUnit.SECONDS);
            try {
                socket.connect(peer.socketAddress(), 3000); socket.setSoTimeout(8000);
                write.write(socket.getOutputStream()); LanProtocol.verifyAck(socket.getInputStream(), peer.key);
            } finally { deadline.shutdownNow(); }
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
