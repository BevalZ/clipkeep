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
    public static final int CHUNK_BYTES = 256 * 1024;
    private static final int CHUNK_META_BYTES = 1 + 16 + 4 + 4 + 4 + 8;
    // At most one platform DNS lookup can remain in flight after a timeout.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> { Thread t = new Thread(task, "clipkeep-dns"); t.setDaemon(true); return t; });
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
        finally { lookup.cancel(true); if (lookup instanceof Runnable) DNS.remove((Runnable)lookup); }
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
        public final Chunk chunk;
        Message(int magic, byte[] bytes) { this.magic = magic; this.bytes = bytes; this.chunk = null; }
        Message(int magic, Chunk chunk) { this.magic = magic; this.bytes = null; this.chunk = chunk; }
        public String text() throws IOException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        }
        public boolean image() { return magic == 0x434b4931; }
        public boolean probeReply() { return magic == 0x434b5231; }
        public boolean probe() { return magic == 0x434b4831; }
        public boolean pair() { return magic == 0x434b5031; }
        public boolean capabilityProbe() { return magic == 0x434b4832; }
        public boolean capabilityReply() { return magic == 0x434b5232; }
        public boolean chunkReceipt() { return magic == 0x434b4132; }
        public boolean chunk() { return magic == 0x434b4331; }
    }
    public static final class Chunk {
        public final byte[] id, kind, bytes;
        public final long index, total, totalBytes;
        Chunk(byte[] id, byte[] kind, long index, long total, long totalBytes, byte[] bytes) { this.id = id; this.kind = kind; this.index = index; this.total = total; this.totalBytes = totalBytes; this.bytes = bytes; }
    }
    private static int limit(int magic) throws IOException {
        if (magic == 0x434b4c31) return MAX_TEXT_BYTES;
        if (magic == 0x434b4931) return MAX_IMAGE_BYTES;
        if (magic == 0x434b5031) return 1024;
        if (magic == 0x434b4831 || magic == 0x434b5231) return 32;
        if (magic == 0x434b4132) return 16;
        if (magic == 0x434b4832) return 32;
        if (magic == 0x434b5232) return 33;
        if (magic == 0x434b4331) return CHUNK_META_BYTES + CHUNK_BYTES;
        throw new IOException("协议版本不匹配，请更新接收端");
    }
    private static byte[] aad(int magic) throws IOException {
        limit(magic);
        if (magic == 0x434b5231) return "clipkeep-lan-probe-reply-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b4831) return "clipkeep-lan-probe-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b4132) return "clipkeep-lan-chunk-receipt-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b5232) return "clipkeep-lan-capability-reply-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b4832) return "clipkeep-lan-capability-v1".getBytes(StandardCharsets.UTF_8);
        if (magic == 0x434b4331) return "clipkeep-lan-chunk-v1".getBytes(StandardCharsets.UTF_8);
        return magic == 0x434b4c31 ? AAD : (magic == 0x434b4931 ? "clipkeep-lan-image-v1" : "clipkeep-lan-pair-v1").getBytes(StandardCharsets.UTF_8);
    }
    public static void writePayload(OutputStream stream, byte[] key, int magic, byte[] plain) throws IOException, GeneralSecurityException {
        if (((magic == 0x434b4831 || magic == 0x434b5231 || magic == 0x434b4832) && plain.length != 32) || (magic == 0x434b5232 && plain.length != 33) || (magic == 0x434b4132 && plain.length != 16) || plain.length == 0 || plain.length > limit(magic)) throw new IOException("内容长度超限");
        byte[] nonce = nonce();
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad(magic)).doFinal(plain);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(magic); out.write(nonce); out.writeInt(encrypted.length); out.write(encrypted); out.flush();
    }
    public static void writeChunk(OutputStream stream, byte[] key, byte[] id, byte[] kind, long index, long total, long totalBytes, byte[] bytes) throws IOException, GeneralSecurityException {
        if (id.length != 16) throw new IOException("分块 ID 无效");
        validateChunk(kind, index, total, totalBytes, bytes.length);
        ByteBuffer plain = ByteBuffer.allocate(CHUNK_META_BYTES + bytes.length).order(java.nio.ByteOrder.BIG_ENDIAN);
        plain.put((byte)1).put(id).put(kind).putInt((int)index).putInt((int)total).putLong(totalBytes).put(bytes);
        writePayload(stream, key, 0x434b4331, plain.array());
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
        if ((message.probe() || message.probeReply() || message.capabilityProbe()) && plain.length != 32) throw new IOException("探测长度无效");
        if (message.capabilityReply() && plain.length != 33) throw new IOException("能力响应长度无效");
        if (message.chunkReceipt() && plain.length != 16) throw new IOException("分块确认长度无效");
        if (message.chunk()) {
            if (plain.length < CHUNK_META_BYTES || plain[0] != 1) throw new IOException("分块头无效");
            ByteBuffer metadata = ByteBuffer.wrap(plain).order(java.nio.ByteOrder.BIG_ENDIAN);
            metadata.get(); byte[] id = new byte[16]; metadata.get(id); byte[] kind = new byte[4]; metadata.get(kind);
            long index = Integer.toUnsignedLong(metadata.getInt()), total = Integer.toUnsignedLong(metadata.getInt()), totalBytes = metadata.getLong();
            validateChunk(kind, index, total, totalBytes, plain.length - CHUNK_META_BYTES);
            return new Message(magic, new Chunk(id, kind, index, total, totalBytes, Arrays.copyOfRange(plain, CHUNK_META_BYTES, plain.length)));
        }
        if (!message.image() && !message.probe() && !message.probeReply() && !message.capabilityProbe() && !message.capabilityReply() && !message.chunkReceipt() && message.text().trim().isEmpty()) throw new IOException("空文本");
        return message;
    }
    private static void validateChunk(byte[] kind, long index, long total, long totalBytes, int length) throws IOException {
        int type = kind.length == 4 ? ByteBuffer.wrap(kind).getInt() : 0;
        long limit = type == 0x434b4c31 ? MAX_TEXT_BYTES : MAX_IMAGE_BYTES;
        if ((type != 0x434b4c31 && type != 0x434b4931) || totalBytes <= 0 || totalBytes > limit || index < 0 || index >= total || total != (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES) throw new IOException("分块范围无效");
        if (length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES)) throw new IOException("分块长度无效");
    }
    public static Message assemble(InputStream stream, byte[] key, Message message) throws IOException, GeneralSecurityException {
        if (!message.chunk()) return message;
        byte[] bytes = readChunked(stream, key, message);
        Message result = new Message(ByteBuffer.wrap(message.chunk.kind).getInt(), bytes);
        if (!result.image() && result.text().trim().isEmpty()) throw new IOException("空文本");
        return result;
    }
    public static byte[] readChunked(InputStream stream, byte[] key, Message first) throws IOException, GeneralSecurityException {
        if (!first.chunk() || first.chunk == null) throw new IOException("需要分块");
        Chunk head = first.chunk;
        long limit = Arrays.equals(head.kind, new byte[]{'C','K','L','1'}) ? MAX_TEXT_BYTES : MAX_IMAGE_BYTES;
        if (head.index != 0 || head.totalBytes > limit) throw new IOException("分块起始位置无效");
        byte[] output = new byte[(int)head.totalBytes];
        System.arraycopy(head.bytes, 0, output, 0, head.bytes.length); int position = head.bytes.length;
        for (long expected = 1; expected < head.total; expected++) {
            Message next = readMessage(stream, key); if (!next.chunk() || next.chunk == null) throw new IOException("分块序列中断");
            Chunk chunk = next.chunk;
            if (!Arrays.equals(head.id, chunk.id) || !Arrays.equals(head.kind, chunk.kind) || chunk.index != expected || chunk.total != head.total || chunk.totalBytes != head.totalBytes) throw new IOException("分块序列无效");
            if (position + chunk.bytes.length > output.length) throw new IOException("分块总长度超限");
            System.arraycopy(chunk.bytes, 0, output, position, chunk.bytes.length); position += chunk.bytes.length;
        }
        if (position != head.totalBytes) throw new IOException("分块总长度不匹配");
        return output;
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
