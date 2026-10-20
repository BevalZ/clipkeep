package com.clipkeep.android;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Byte-for-byte compatible with native/src/lan.rs (CKL1). No Android dependencies. */
public final class LanProtocol {
    public static final int PORT = 45788;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
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
            return new InetSocketAddress(InetAddress.getByName(address.substring(0, address.indexOf(':'))), PORT);
        }
    }

    public static boolean allowed(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress();
    }

    public static Peer parseCode(String code) throws IOException {
        try {
            String body = code.trim();
            if (!body.startsWith(PREFIX)) throw new IllegalArgumentException();
            String[] parts = body.substring(PREFIX.length()).split("/", -1);
            if (parts.length != 2 || !parts[0].endsWith(":" + PORT)) throw new IllegalArgumentException();
            String host = parts[0].substring(0, parts[0].indexOf(':'));
            if (!parts[0].equals(host + ":" + PORT)) throw new IllegalArgumentException();
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException();
            byte[] ip = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                int value = Integer.parseInt(octets[i]);
                if (value > 255) throw new IllegalArgumentException();
                ip[i] = (byte) value;
            }
            if (!allowed(InetAddress.getByAddress(ip))) throw new IllegalArgumentException();
            if (!parts[1].matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            byte[] key = Base64.getUrlDecoder().decode(parts[1]);
            if (key.length != 32) throw new IllegalArgumentException();
            return new Peer(parts[0], key);
        } catch (IllegalArgumentException e) { throw new IOException("配对码无效：仅支持局域网 IPv4 和端口 45788", e); }
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
        byte[] nonce = nonce();
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, AAD).doFinal(plain);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(0x434b4c31);
        out.write(nonce);
        out.writeInt(encrypted.length);
        out.write(encrypted);
        out.flush();
    }

    public static String readText(InputStream stream, byte[] key) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != 0x434b4c31) throw new IOException("协议版本不匹配");
        byte[] nonce = new byte[12]; in.readFully(nonce);
        int length = in.readInt();
        if (length < 16 || length > MAX_TEXT_BYTES + 16) throw new IOException("文本长度超限");
        byte[] encrypted = new byte[length]; in.readFully(encrypted);
        byte[] plain = cipher(Cipher.DECRYPT_MODE, key, nonce, AAD).doFinal(encrypted);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plain)).toString();
        if (text.trim().isEmpty()) throw new IOException("空文本");
        return text;
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
