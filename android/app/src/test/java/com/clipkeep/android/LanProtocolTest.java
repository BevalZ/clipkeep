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
    @Test public void imagePairAndTailnetProtocol() throws Exception {
        byte[] frame = hex("434b4931000102030405060708090a0b0000005600ab4647241dbf4ab7833ffed1554a31c970b2e65174adb8ee912fc575dfb3f865802cf3eb57f5d9634aeabb9d5b4fd36184ac74fcd731022fa5d907904fd793373c128f85966249af9ed7198407482169e46e73fa01");
        LanProtocol.Message image = LanProtocol.readMessage(new ByteArrayInputStream(frame), key());
        assertTrue(image.image()); assertEquals((byte)137, image.bytes[0]);
        frame[3] = '2'; assertThrows(IOException.class, () -> LanProtocol.readMessage(new ByteArrayInputStream(frame), key()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LanProtocol.writePayload(out, key(), 0x434b5031, "pair".getBytes());
        assertTrue(LanProtocol.readMessage(new ByteArrayInputStream(out.toByteArray()), key()).pair());
        byte[] wrongType = out.toByteArray(); wrongType[2] = 'L';
        assertThrows(Exception.class, () -> LanProtocol.readMessage(new ByteArrayInputStream(wrongType), key()));
        for (String host : new String[]{"100.64.0.1", "100.127.255.254"}) assertTrue(LanProtocol.allowed(java.net.InetAddress.getByName(host)));
        for (String host : new String[]{"100.63.255.255", "100.128.0.1", "8.8.8.8"}) assertFalse(LanProtocol.allowed(java.net.InetAddress.getByName(host)));
        assertThrows(IOException.class, () -> LanProtocol.writePayload(out, key(), 0x434b5031, new byte[1025]));
        assertThrows(IOException.class, () -> LanProtocol.writePayload(out, key(), 0x434b4931, new byte[LanProtocol.MAX_IMAGE_BYTES + 1]));
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
    @Test public void pairingAcceptsPrivateAddressesAndMagicDnsNames() throws Exception {
        String suffix = ":45788/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        assertEquals("192.168.1.2:45788", LanProtocol.parseCode("cklan1://192.168.1.2" + suffix).address);
        assertEquals("desktop.tail123.ts.net:45788", LanProtocol.parseCode("cklan1://DESKTOP.tail123.ts.net" + suffix).address);
        assertEquals("desktop:45788", LanProtocol.parseCode("cklan1://desktop" + suffix).address);
        for (String host : new String[]{"8.8.8.8", "example.com", "desktop.ts.net.evil.com", "a..ts.net", "-bad", "bad-", "127.1", "192.168.1.256", "192.168.01.2", "[::1]", "192.168.1.2:123"}) {
            assertThrows(IOException.class, () -> LanProtocol.parseCode("cklan1://" + host + suffix));
        }
    }
    @Test public void probeVectorsRequireDistinctResponseAndRestrictedResolution() throws Exception {
        byte[] request = hex("434b4831000102030405060708090a0b0000003089fa0a032d12a347bf8a35f89410006cd961a0f44561bbaefe8e35de69ddb8230a2c9c54999acd30f0c6eb5d4cdc7484");
        byte[] reply = hex("434b5231000102030405060708090a0b0000003089fa0a032d12a347bf8a35f89410006cd961a0f44561bbaefe8e35de69ddb8235dbe86d7d3ae38edcdb1e4ce757de6a7");
        assertTrue(LanProtocol.readMessage(new ByteArrayInputStream(request), key()).probe());
        assertTrue(LanProtocol.readMessage(new ByteArrayInputStream(reply), key()).probeReply());
        request[2] = 'R';
        assertThrows(Exception.class, () -> LanProtocol.readMessage(new ByteArrayInputStream(request), key()));
        assertThrows(IOException.class, () -> LanProtocol.writePayload(new ByteArrayOutputStream(), key(), 0x434b4831, new byte[31]));
        assertThrows(IOException.class, () -> LanProtocol.restrictedAddress(new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8"), java.net.InetAddress.getByName("::1")}));
        // Cached lookups may finish before the worker returns to its queue.
        for (int i = 0; i < 20; i++) assertTrue(LanProtocol.allowed(LanProtocol.resolve("localhost")));
        assertEquals("100.64.0.1", LanProtocol.restrictedAddress(new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8"), java.net.InetAddress.getByName("100.64.0.1")}).getHostAddress());
    }
    @Test public void chunkedPayloadRoundTripsAndRejectsReorderedChunks() throws Exception {
        byte[] key = key(), id = new byte[16]; Arrays.fill(id, (byte) 7);
        byte[] payload = new byte[700000]; for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i % 251);
        int total = (payload.length + LanProtocol.CHUNK_BYTES - 1) / LanProtocol.CHUNK_BYTES;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int index = 0; index < total; index++) {
            int start = index * LanProtocol.CHUNK_BYTES, end = Math.min(payload.length, start + LanProtocol.CHUNK_BYTES);
            LanProtocol.writeChunk(wire, key, id, new byte[]{'C','K','I','1'}, index, total, payload.length, Arrays.copyOfRange(payload, start, end));
        }
        byte[] bytes = wire.toByteArray(); ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        LanProtocol.Message first = LanProtocol.readMessage(input, key); assertTrue(first.chunk());
        assertArrayEquals(payload, LanProtocol.readChunked(input, key, first));
        ByteArrayOutputStream reordered = new ByteArrayOutputStream();
        int frameBytes = 20 + 16 + 37 + LanProtocol.CHUNK_BYTES;
        reordered.write(bytes, 0, frameBytes); reordered.write(bytes, frameBytes * 2, bytes.length - frameBytes * 2); reordered.write(bytes, frameBytes, frameBytes);
        ByteArrayInputStream bad = new ByteArrayInputStream(reordered.toByteArray());
        LanProtocol.Message head = LanProtocol.readMessage(bad,key); assertThrows(IOException.class, () -> LanProtocol.readChunked(bad,key,head));
    }
    @Test public void independentChunkVectorAndStrictLimits() throws Exception {
        byte[] wire = hex("434b4331000102030405060708090a0b0000003f88fb09022a13a046b08b36f99311036dc633f9ab6074adb9e6972fc574c0a63cec802cf3ecfb391ed2afe128fa0c0feb19ae6a7e8a1e38df924e47613c2804"); ByteArrayInputStream input = new ByteArrayInputStream(wire);
        LanProtocol.Message first = LanProtocol.readMessage(input, key());
        assertEquals("分块😀", LanProtocol.assemble(input, key(), first).text());
        assertTrue(LanProtocol.readMessage(new ByteArrayInputStream(hex("434b4132000102030405060708090a0b0000002089fa0a032d12a347bf8a35f89410006ccd9be81707f637cabe7534eb03f678c4")), key()).chunkReceipt());
        wire[25] ^= 1; assertThrows(Exception.class, () -> LanProtocol.readMessage(new ByteArrayInputStream(wire),key()));
        assertThrows(IOException.class, () -> LanProtocol.writeChunk(new ByteArrayOutputStream(),key(),new byte[16],new byte[]{'C','K','L','1'},0,1,LanProtocol.MAX_TEXT_BYTES+1,new byte[]{1}));
        assertThrows(IOException.class, () -> LanProtocol.writeChunk(new ByteArrayOutputStream(),key(),new byte[16],new byte[]{'C','K','L','1'},0,4294967295L,1,new byte[]{1}));
    }

}
