package com.clipkeep.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class TransferTest {
    @Test public void retriesConnectBeforeSendingButNeverRetriesMissingAck() throws Exception {
        byte[] key = LanProtocol.newKey();
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> {
                Thread.sleep(150);
                try (ServerSocket server = new ServerSocket(port); Socket socket = server.accept()) {
                    server.setSoTimeout(1000); socket.setSoTimeout(3000);
                    String text = LanProtocol.readText(socket.getInputStream(), key);
                    // The receiver saved data but lost the ACK. A retry would duplicate effects.
                    socket.close();
                    assertThrows(SocketTimeoutException.class, server::accept);
                    return text;
                }
            });
            assertThrows(IOException.class, () -> Transfer.exchange(target, out -> LanProtocol.writeText(out, key, "only once"), in -> LanProtocol.verifyAck(in, key)));
            assertEquals("only once", result.get(8, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
    @Test public void probeRejectsReflectedRequestAndAuthenticatesFreshReply() throws Exception {
        byte[] key = LanProtocol.newKey();
        for (boolean reflect : new boolean[]{true, false}) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(0)) {
                server.setSoTimeout(3000);
                InetSocketAddress target = new InetSocketAddress("127.0.0.1", server.getLocalPort());
                Future<?> response = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        LanProtocol.Message request = LanProtocol.readMessage(socket.getInputStream(), key);
                        LanProtocol.writePayload(socket.getOutputStream(), key, reflect ? 0x434b4831 : 0x434b5231, request.bytes);
                    }
                    return null;
                });
                if (reflect) assertThrows(IOException.class, () -> Transfer.probe(target, key));
                else Transfer.probe(target, key);
                response.get(5, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        }
    }
}
