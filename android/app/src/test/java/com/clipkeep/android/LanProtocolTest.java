package com.clipkeep.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;

public class LanProtocolTest {
    private byte[] key() { byte[] key = new byte[32]; for (int i = 0; i < 32; i++) key[i] = (byte) i; return key; }
    private byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    @Test public void independentVectorMatchesRust() throws Exception {
        byte[] frame = hex("434b4c31000102030405060708090a0b00000033ea9761704272c030976782537db8b369881ed6953e1dc9990411bbe522a9c85883f75ffe2fc7da8685feab4ff3264fe82e7a86");
        assertEquals("clipkeep 你好\nAndroid ↔ Windows", LanProtocol.readText(new ByteArrayInputStream(frame), key()));
        byte[] ack = hex("000102030405060708090a0bc6b04639412ed260990206761ffa37ccf928");
        LanProtocol.verifyAck(new ByteArrayInputStream(ack), key());
        frame[20] ^= 1;
        assertThrows(Exception.class, () -> LanProtocol.readText(new ByteArrayInputStream(frame), key()));
        ack[29] ^= 1;
        assertThrows(Exception.class, () -> LanProtocol.verifyAck(new ByteArrayInputStream(ack), key()));
    }
    @Test public void unicodeRoundTripAndMaximumPayload() throws Exception {
        for (String text : new String[]{"中文 😀\nline", "x".repeat(LanProtocol.MAX_TEXT_BYTES)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LanProtocol.writeText(out, key(), text);
            assertEquals(text, LanProtocol.readText(new ByteArrayInputStream(out.toByteArray()), key()));
        }
        assertThrows(IOException.class, () -> LanProtocol.writeText(new ByteArrayOutputStream(), key(), "中".repeat(800000)));
    }
    @Test public void invalidLengthAndTruncationFailBeforePayloadAllocation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LanProtocol.writeText(out, key(), "test"); byte[] frame = out.toByteArray();
        Arrays.fill(frame, 16, 20, (byte) 127);
        assertThrows(IOException.class, () -> LanProtocol.readText(new ByteArrayInputStream(frame), key()));
        assertThrows(IOException.class, () -> LanProtocol.readText(new ByteArrayInputStream(new byte[]{'C'}), key()));
    }
    @Test public void pairingOnlyAcceptsLiteralLocalAddresses() throws Exception {
        String suffix = ":45788/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        assertEquals("192.168.1.2:45788", LanProtocol.parseCode("cklan1://192.168.1.2" + suffix).address);
        for (String host : new String[]{"8.8.8.8", "example.com", "192.168.1.256", "192.168.01.2", "[::1]", "192.168.1.2:123"}) {
            assertThrows(IOException.class, () -> LanProtocol.parseCode("cklan1://" + host + suffix));
        }
    }
}
