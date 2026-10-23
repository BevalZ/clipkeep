package com.clipkeep.android;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import org.json.*;

/** Bounded UDP discovery: Wi-Fi broadcasts plus explicitly listed Tailnet hosts. No keys on the wire. */
final class Discovery {
    static final class Device {
        final String address, name;
        Device(String address, String name) { this.address = address; this.name = name; }
        @Override public String toString() { return name + " · " + address; }
    }
    static List<Device> scan(String hosts) throws Exception {
        Set<InetAddress> targets = new HashSet<>(), local = new HashSet<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement(); if (!network.isUp()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                local.add(address.getAddress());
                if (address.getBroadcast() != null) targets.add(address.getBroadcast());
            }
        }
        String[] candidates = hosts.trim().isEmpty() ? new String[0] : hosts.trim().split("[\\s,;]+", -1);
        if (candidates.length > 32) throw new IOException("一次最多探测 32 台 Tailnet 设备");
        long resolutionUntil = System.nanoTime() + 8_000_000_000L;
        for (String host : candidates) {
            if (System.nanoTime() > resolutionUntil) throw new IOException("名称解析耗时过长，请分批扫描");
            // Explicit names only; never enumerate the entire Tailnet.
            String code = LanProtocol.PREFIX + host + ":" + LanProtocol.PORT + "/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            targets.add(LanProtocol.parseCode(code).socketAddress().getAddress());
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        byte[] query = ("CKD1? " + nonce).getBytes(StandardCharsets.US_ASCII);
        Map<String,Device> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); socket.setSoTimeout(250);
            for (InetAddress target : targets) try { socket.send(new DatagramPacket(query, query.length, target, LanProtocol.PORT)); } catch (IOException ignored) { }
            long until = System.nanoTime() + 3_000_000_000L;
            byte[] buffer = new byte[1024];
            while (System.nanoTime() < until && found.size() < 64) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    if (local.contains(packet.getAddress()) || !LanProtocol.allowed(packet.getAddress()) || packet.getPort() != LanProtocol.PORT) continue;
                    JSONObject json = new JSONObject(new String(buffer, 0, packet.getLength(), StandardCharsets.UTF_8));
                    if (!"CKD1".equals(json.optString("protocol")) || !nonce.equals(json.optString("nonce")) || json.optInt("port") != LanProtocol.PORT) continue;
                    String address = packet.getAddress().getHostAddress(), name = json.optString("name", "clipkeep");
                    found.put(address, new Device(address, name.substring(0, Math.min(40, name.length()))));
                } catch (SocketTimeoutException ignored) { } catch (JSONException ignored) { }
            }
        }
        return new ArrayList<>(found.values());
    }
    static void respond(DatagramSocket socket, String name) {
        byte[] buffer = new byte[128]; long window = System.nanoTime(); int budget = 20;
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                if (System.nanoTime() - window > 1_000_000_000L) { budget = 20; window = System.nanoTime(); }
                if (budget == 0 || !LanProtocol.allowed(packet.getAddress()) || packet.getLength() != 38) continue;
                String query = new String(buffer, 0, packet.getLength(), StandardCharsets.US_ASCII);
                if (!query.matches("CKD1\\? [a-fA-F0-9]{32}")) continue;
                budget--;
                byte[] response = new JSONObject().put("protocol", "CKD1").put("nonce", query.substring(6))
                    .put("name", name).put("port", LanProtocol.PORT).put("images", true).put("pair", true).put("probe", true).toString().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
            } catch (Exception ignored) { if (socket.isClosed()) return; }
        }
    }
}
