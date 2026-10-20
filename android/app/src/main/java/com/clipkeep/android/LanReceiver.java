package com.clipkeep.android;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Service-owned, single-connection receiver with absolute deadlines and authenticated ACKs. */
final class LanReceiver implements AutoCloseable {
    interface Listener { void received(long id); void failed(Exception error); }
    private final ServerSocket server;
    private final DatagramSocket discovery;
    private final byte[] key;
    private final ClipStore store;
    private final Context context;
    private final Listener listener;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private Socket active;
    LanReceiver(Context context, byte[] key, ClipStore store, Listener listener) throws IOException {
        this.context = context.getApplicationContext(); this.key = key.clone(); this.store = store; this.listener = listener;
        server = new ServerSocket();
        DatagramSocket udp = null;
        try {
            server.setReuseAddress(true); server.bind(new InetSocketAddress("0.0.0.0", LanProtocol.PORT), 2);
            udp = new DatagramSocket(null); udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress("0.0.0.0", LanProtocol.PORT));
            discovery = udp;
        } catch (IOException e) { if (udp != null) udp.close(); server.close(); deadlines.shutdownNow(); throw e; }
        Thread thread = new Thread(this::receive, "clipkeep-receive"); thread.setDaemon(true); thread.start();
        Thread probe = new Thread(() -> Discovery.respond(discovery, android.os.Build.MODEL), "clipkeep-discovery"); probe.setDaemon(true); probe.start();
    }
    private void receive() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                synchronized (this) { if (closed) return; active = socket; }
                if (!LanProtocol.allowed(socket.getInetAddress())) continue;
                socket.setSoTimeout(5000);
                ScheduledFuture<?> deadline = deadlines.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 15, TimeUnit.SECONDS);
                try {
                    LanProtocol.Message message = LanProtocol.readMessage(socket.getInputStream(), key);
                    if (closed) return;
                    long id = -1;
                    if (message.pair()) {
                        JSONObject pair = new JSONObject(message.text());
                        LanProtocol.Peer peer = LanProtocol.parseCode(LanProtocol.PREFIX + socket.getInetAddress().getHostAddress() + ":" + LanProtocol.PORT + "/" + pair.getString("key"));
                        LanSettings.add(context, pair.getString("name"), peer);
                    } else id = message.image() ? store.saveImage(message.bytes) : store.save(message.text());
                    LanProtocol.writeAck(socket.getOutputStream(), key);
                    listener.received(id);
                } finally { deadline.cancel(false); }
            } catch (Exception error) { if (!closed) listener.failed(error); }
            finally { synchronized (this) { active = null; } }
        }
    }
    @Override public synchronized void close() {
        closed = true; discovery.close(); deadlines.shutdownNow();
        try { server.close(); } catch (IOException ignored) { }
        if (active != null) try { active.close(); } catch (IOException ignored) { }
    }
}
