package com.clipkeep.android;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Byte-for-byte compatible with native/src/lan.rs (CKL1). No Android dependencies. */
public final class LanProtocol {
    public static final int PORT = 45788;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = 16_000_000;
    // At most one platform DNS lookup can remain in flight after a timeout.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> { Thread t = new Thread(task, "clipkeep-dns"); t.setDaemon(true); return t; });
    public static final String PREFIX = "cklan1://";
    private static final byte[] AAD = "clipkeep-lan-text-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACK_AAD = "clipkeep-lan-ack-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Peer {
        public final String address;
        public final byte[] key;
        private Peer(String address, byte[] key) { this.address = address; this.key = key; }
        public String code() { return PREFIX + address + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key); }
        public InetSocketAddress socketAddress() throws IOException {
            return new InetSocketAddress(resolve(address.substring(0, address.indexOf(':'))), PORT);
        }
    }

    public static boolean allowed(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] ip = address.getAddress();
        return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress()
            || ((ip[0] & 255) == 100 && (ip[1] & 255) >= 64 && (ip[1] & 255) <= 127);
    }

    public static Peer parseCode(String code) throws IOException {
        try {
            String body = code.trim();
            if (!body.startsWith(PREFIX)) throw new IllegalArgumentException();
            String[] parts = body.substring(PREFIX.length()).split("/", -1);
            if (parts.length != 2 || !parts[0].endsWith(":" + PORT)) throw new IllegalArgumentException();
            String host = parts[0].substring(0, parts[0].indexOf(':'));
            if (!parts[0].equals(host + ":" + PORT)) throw new IllegalArgumentException();
            validateHost(host);
            if (!parts[1].matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            byte[] key = Base64.getUrlDecoder().decode(parts[1]);
            if (key.length != 32) throw new IllegalArgumentException();
            return new Peer(parts[0].toLowerCase(Locale.ROOT), key);
        } catch (IllegalArgumentException e) { throw new IOException("配对码无效：支持私网 IPv4、MagicDNS 名称和端口 45788", e); }
    }

    static void validateHost(String host) throws IOException {
        if (host.matches("[0-9.]+")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) throw new IOException("IPv4 地址无效");
            byte[] ip = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IOException("IPv4 地址无效");
                int value = Integer.parseInt(octets[i]);
                if (value > 255) throw new IOException("IPv4 地址无效");
                ip[i] = (byte)value;
            }
            if (!allowed(InetAddress.getByAddress(ip))) throw new IOException("仅允许私网或 Tailnet IPv4");
            return;
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.length() > 253 || (name.contains(".") && !name.endsWith(".ts.net"))) throw new IOException("请输入 MagicDNS 短名称或 .ts.net 完整名称");
        for (String label : name.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IOException("MagicDNS 名称无效");
    }
    static InetAddress restrictedAddress(InetAddress[] addresses) throws IOException {
        for (InetAddress address : addresses) if (allowed(address)) return address;
        throw new IOException("名称未解析到私网或 Tailnet IPv4，请检查 VPN / MagicDNS");
    }
    static InetAddress resolve(String host) throws IOException {
        validateHost(host);
        if (host.matches("[0-9.]+")) return InetAddress.getByName(host);
        Future<InetAddress[]> lookup;
        try { lookup = DNS.submit(() -> InetAddress.getAllByName(host)); }
        catch (RejectedExecutionException error) { throw new IOException("DNS 查询尚未结束，请稍后重试", error); }
        try { return restrictedAddress(lookup.get(3, TimeUnit.SECONDS)); }
        catch (TimeoutException error) { throw new IOException("DNS 查询超时，请检查 Tailscale / MagicDNS", error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("DNS 查询已取消", error); }
        catch (ExecutionException error) { throw new IOException("无法解析设备名称，请检查 Tailscale / MagicDNS", error.getCause()); }
        finally { lookup.cancel(true); }
    }

    public static byte[] newKey() { byte[] key = new byte[32]; RANDOM.nextBytes(key); return key; }
    private static byte[] nonce() { byte[] value = new byte[12]; RANDOM.nextBytes(value); return value; }
    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        Cipher cipher;
        try { cipher = Cipher.getInstance("ChaCha20-Poly1305"); }
        catch (GeneralSecurityException unavailable) { cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding"); }
        cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    public static void writeText(OutputStream stream, byte[] key, String text) throws IOException, GeneralSecurityException {
        if (text.length() > MAX_TEXT_BYTES || text.trim().isEmpty()) throw new IOException("文本为空或超过 2 MB");
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_TEXT_BYTES) throw new IOException("文本超过 2 MB");
        writePayload(stream, key, 0x434b4c31, plain);
    }
    public static final class Message {
        public final int magic;
        public final byte[] bytes;
        Message(int magic, byte[] bytes) { this.magic = magic; this.bytes = bytes; }
        public String text() throws IOException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        }
        public boolean image() { return magic == 0x434b4931; }
        public boolean probeReply() { return magic == 0x434b5231; }
        public boolean probe() { return magic == 0x434b4831; }
        public boolean pair() { return magic == 0x434b5031; }
    }
    private static int limit(int magic) throws IOException {
        if (magic == 0x434b4c31) return MAX_TEXT_BYTES;
        if (magic == 0x434b4931) return MAX_IMAGE_BYTES;
        if (magic == 0x434b5031) return 1024;
        if (magic == 0x434b4831 || magic == 0x434b5231) return 32;
        throw new IOException("协议版本不匹配，请更新接收端");
    }
    private static byte[] aad(int magic) throws IOException {
        limit(magic);
        if (magic == 0x434b5231) return "clipkeep-lan-probe-reply-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b4831) return "clipkeep-lan-probe-v1".getBytes(StandardCharsets.UTF_8);
        return magic == 0x434b4c31 ? AAD : (magic == 0x434b4931 ? "clipkeep-lan-image-v1" : "clipkeep-lan-pair-v1").getBytes(StandardCharsets.UTF_8);
    }
    public static void writePayload(OutputStream stream, byte[] key, int magic, byte[] plain) throws IOException, GeneralSecurityException {
        if (((magic == 0x434b4831 || magic == 0x434b5231) && plain.length != 32) || plain.length == 0 || plain.length > limit(magic)) throw new IOException("内容长度超限");
        byte[] nonce = nonce();
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad(magic)).doFinal(plain);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(magic); out.write(nonce); out.writeInt(encrypted.length); out.write(encrypted); out.flush();
    }
    public static Message readMessage(InputStream stream, byte[] key) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(stream);
        int magic = in.readInt(), maximum = limit(magic);
        byte[] nonce = new byte[12]; in.readFully(nonce);
        int length = in.readInt();
        if (length < 17 || length > maximum + 16) throw new IOException("内容长度超限");
        byte[] encrypted = new byte[length]; in.readFully(encrypted);
        byte[] plain = cipher(Cipher.DECRYPT_MODE, key, nonce, aad(magic)).doFinal(encrypted);
        Message message = new Message(magic, plain);
        if ((message.probe() || message.probeReply()) && plain.length != 32) throw new IOException("探测长度无效");
        if (!message.image() && !message.probe() && !message.probeReply() && message.text().trim().isEmpty()) throw new IOException("空文本");
        return message;
    }
    public static String readText(InputStream stream, byte[] key) throws IOException, GeneralSecurityException {
        Message message = readMessage(stream, key);
        if (message.magic != 0x434b4c31) throw new IOException("需要文本");
        return message.text();
    }

    public static void writeAck(OutputStream stream, byte[] key) throws IOException, GeneralSecurityException {
        byte[] nonce = nonce();
        stream.write(nonce);
        stream.write(cipher(Cipher.ENCRYPT_MODE, key, nonce, ACK_AAD).doFinal(new byte[]{'O', 'K'}));
        stream.flush();
    }
    public static void verifyAck(InputStream stream, byte[] key) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(stream);
        byte[] nonce = new byte[12]; in.readFully(nonce);
        byte[] receipt = new byte[18]; in.readFully(receipt);
        byte[] plain = cipher(Cipher.DECRYPT_MODE, key, nonce, ACK_AAD).doFinal(receipt);
        if (!Arrays.equals(plain, new byte[]{'O', 'K'})) throw new IOException("设备未确认接收");
    }
}
