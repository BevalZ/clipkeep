package com.clipkeep.android;

import java.io.*;
import java.net.*;

/** Foreground-only receiver. One connection at a time; closing it interrupts all blocking IO. */
final class LanReceiver implements AutoCloseable {
    interface Listener { void received(); void failed(Exception error); }
    private final ServerSocket server;
    private final byte[] key;
    private final ClipStore store;
    private final Listener listener;
    private volatile boolean closed;
    private Socket active;
    LanReceiver(byte[] key, ClipStore store, Listener listener) throws IOException {
        this.key = key.clone(); this.store = store; this.listener = listener;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0", LanProtocol.PORT), 2);
        Thread thread = new Thread(this::receive, "clipkeep-receive");
        thread.setDaemon(true); thread.start();
    }
    private void receive() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                synchronized (this) { if (closed) return; active = socket; }
                if (!LanProtocol.allowed(socket.getInetAddress())) continue;
                socket.setSoTimeout(2000);
                String text = LanProtocol.readText(socket.getInputStream(), key);
                if (closed) return;
                store.save(text);
                LanProtocol.writeAck(socket.getOutputStream(), key);
                listener.received();
            } catch (Exception error) {
                if (!closed) listener.failed(error);
            } finally { synchronized (this) { active = null; } }
        }
    }
    @Override public synchronized void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        if (active != null) try { active.close(); } catch (IOException ignored) { }
    }
}
