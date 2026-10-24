//! Shared wire format, kept independent of the desktop runtime for compatibility tests.
use chacha20poly1305::{
    ChaCha20Poly1305, Key, KeyInit, Nonce,
    aead::{Aead, AeadInOut, Payload},
};
#[cfg(test)]
use std::io::Cursor;
use std::io::Read;
const MAGIC: &[u8; 4] = b"CKL1";
const AAD: &[u8] = b"clipkeep-lan-text-v1";
const ACK_AAD: &[u8] = b"clipkeep-lan-ack-v1";
pub(crate) const MAX_TEXT_BYTES: usize = 2 * 1024 * 1024;
#[cfg(test)]
pub(crate) const MAX_CIPHER_BYTES: usize = MAX_TEXT_BYTES + 16;
pub(crate) const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const MAX_IMAGE_PIXELS: u64 = 16_000_000;
pub(crate) const CHUNK_BYTES: usize = 256 * 1024;
const CHUNK_META_BYTES: usize = 1 + 16 + 4 + 4 + 4 + 8;
#[derive(Debug, PartialEq)]
pub(crate) enum Message {
    Text(String),
    Image(Vec<u8>),
    Pair(String),
    Probe([u8; 32]),
    ProbeReply([u8; 32]),
    CapabilityProbe([u8; 32]),
    CapabilityReply([u8; 33]),
    ChunkReceipt([u8; 16]),
    Chunk {
        id: [u8; 16],
        kind: [u8; 4],
        index: u32,
        total: u32,
        total_bytes: u64,
        bytes: Vec<u8>,
    },
}
pub(crate) fn allowed_ip(ip: std::net::Ipv4Addr) -> bool {
    let octets = ip.octets();
    ip.is_private()
        || ip.is_link_local()
        || ip.is_loopback()
        || (octets[0] == 100 && (64..=127).contains(&octets[1]))
}
fn format(magic: &[u8]) -> Result<(&'static [u8], usize), String> {
    match magic {
        b"CKL1" => Ok((AAD, MAX_TEXT_BYTES)),
        b"CKI1" => Ok((b"clipkeep-lan-image-v1", MAX_IMAGE_BYTES)),
        b"CKH1" => Ok((b"clipkeep-lan-probe-v1", 32)),
        b"CKR1" => Ok((b"clipkeep-lan-probe-reply-v1", 32)),
        b"CKH2" => Ok((b"clipkeep-lan-capability-v1", 32)),
        b"CKR2" => Ok((b"clipkeep-lan-capability-reply-v1", 33)),
        b"CKA2" => Ok((b"clipkeep-lan-chunk-receipt-v1", 16)),
        b"CKC1" => Ok((b"clipkeep-lan-chunk-v1", CHUNK_META_BYTES + CHUNK_BYTES)),
        b"CKP1" => Ok((b"clipkeep-lan-pair-v1", 1024)),
        _ => Err("协议版本不匹配".into()),
    }
}
pub(crate) fn encrypt_frame(key: &[u8; 32], text: &str) -> Result<Vec<u8>, String> {
    if text.trim().is_empty() {
        return Err("空文本".into());
    }
    encrypt_payload(key, MAGIC, text.as_bytes())
}
pub(crate) fn encrypt_payload(
    key: &[u8; 32],
    magic: &[u8; 4],
    bytes: &[u8],
) -> Result<Vec<u8>, String> {
    let (aad, limit) = format(magic)?;
    if bytes.is_empty()
        || bytes.len() > limit
        || (matches!(magic, b"CKH1" | b"CKR1" | b"CKH2") && bytes.len() != 32)
        || (magic == b"CKR2" && bytes.len() != 33)
        || (magic == b"CKA2" && bytes.len() != 16)
    {
        return Err("内容长度超限".into());
    }
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let mut frame = Vec::with_capacity(20 + bytes.len() + 16);
    frame.extend_from_slice(magic);
    frame.extend_from_slice(&nonce);
    frame.extend_from_slice(&((bytes.len() + 16) as u32).to_be_bytes());
    frame.extend_from_slice(bytes);
    let tag = cipher
        .encrypt_inout_detached(&Nonce::from(nonce), aad, (&mut frame[20..]).into())
        .map_err(|_| "加密失败")?;
    frame.extend_from_slice(&tag);
    Ok(frame)
}

pub(crate) fn encrypt_chunk(
    key: &[u8; 32],
    id: &[u8; 16],
    kind: &[u8; 4],
    index: u32,
    total: u32,
    total_bytes: u64,
    bytes: &[u8],
) -> Result<Vec<u8>, String> {
    validate_chunk(kind, index, total, total_bytes, bytes.len())?;
    let mut plain = Vec::with_capacity(CHUNK_META_BYTES + bytes.len());
    plain.push(1);
    plain.extend_from_slice(id);
    plain.extend_from_slice(kind);
    plain.extend_from_slice(&index.to_be_bytes());
    plain.extend_from_slice(&total.to_be_bytes());
    plain.extend_from_slice(&total_bytes.to_be_bytes());
    plain.extend_from_slice(bytes);
    encrypt_payload(key, b"CKC1", &plain)
}

fn validate_chunk(
    kind: &[u8; 4],
    index: u32,
    total: u32,
    total_bytes: u64,
    length: usize,
) -> Result<(), String> {
    let limit = match kind {
        b"CKL1" => MAX_TEXT_BYTES,
        b"CKI1" => MAX_IMAGE_BYTES,
        _ => return Err("分块类型无效".into()),
    };
    if total_bytes == 0
        || total_bytes > limit as u64
        || index >= total
        || total != total_bytes.div_ceil(CHUNK_BYTES as u64) as u32
    {
        return Err("分块范围无效".into());
    }
    let expected =
        (total_bytes - index as u64 * CHUNK_BYTES as u64).min(CHUNK_BYTES as u64) as usize;
    if length != expected {
        return Err("分块长度无效".into());
    }
    Ok(())
}

pub(crate) fn make_ack(key: &[u8; 32]) -> Result<[u8; 30], String> {
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let receipt = cipher
        .encrypt(
            &Nonce::from(nonce),
            Payload {
                msg: b"OK",
                aad: ACK_AAD,
            },
        )
        .map_err(|_| "确认加密失败")?;
    let mut ack = [0u8; 30];
    ack[..12].copy_from_slice(&nonce);
    ack[12..].copy_from_slice(&receipt);
    Ok(ack)
}

pub(crate) fn verify_ack(key: &[u8; 32], ack: &[u8; 30]) -> Result<(), String> {
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let nonce: [u8; 12] = ack[..12].try_into().unwrap();
    let receipt = cipher
        .decrypt(
            &Nonce::from(nonce),
            Payload {
                msg: &ack[12..],
                aad: ACK_AAD,
            },
        )
        .map_err(|_| "设备确认无效")?;
    if receipt != b"OK" {
        return Err("设备拒绝了文本".into());
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn read_text_frame(reader: &mut impl Read, key: &[u8; 32]) -> Result<String, String> {
    match read_message(reader, key)? {
        Message::Text(text) => Ok(text),
        _ => Err("需要文本".into()),
    }
}
pub(crate) fn read_message(reader: &mut impl Read, key: &[u8; 32]) -> Result<Message, String> {
    let mut header = [0u8; 20];
    reader.read_exact(&mut header).map_err(|e| e.to_string())?;
    let (aad, limit) = format(&header[..4])?;
    let len = u32::from_be_bytes(header[16..20].try_into().unwrap()) as usize;
    if !(17..=limit + 16).contains(&len) {
        return Err("内容长度超限".into());
    }
    let mut ciphertext = vec![0u8; len];
    reader
        .read_exact(&mut ciphertext)
        .map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let nonce: [u8; 12] = header[4..16].try_into().unwrap();
    cipher
        .decrypt_in_place(&Nonce::from(nonce), aad, &mut ciphertext)
        .map_err(|_| "配对码不匹配或数据损坏")?;
    if &header[..4] == b"CKA2" {
        return Ok(Message::ChunkReceipt(
            ciphertext.try_into().map_err(|_| "分块确认长度无效")?,
        ));
    }
    if matches!(&header[..4], b"CKH1" | b"CKR1") {
        let challenge = ciphertext.try_into().map_err(|_| "探测长度无效")?;
        return Ok(if &header[..4] == b"CKH1" {
            Message::Probe(challenge)
        } else {
            Message::ProbeReply(challenge)
        });
    }
    if matches!(&header[..4], b"CKH2" | b"CKR2") {
        if ciphertext.len() != if &header[..4] == b"CKH2" { 32 } else { 33 } {
            return Err("能力探测长度无效".into());
        }
        if &header[..4] == b"CKH2" {
            return Ok(Message::CapabilityProbe(ciphertext.try_into().unwrap()));
        }
        let bytes: [u8; 33] = ciphertext.try_into().unwrap();
        return Ok(Message::CapabilityReply(bytes));
    }
    if &header[..4] == b"CKC1" {
        if ciphertext.len() < CHUNK_META_BYTES || ciphertext[0] != 1 {
            return Err("分块头无效".into());
        }
        let id: [u8; 16] = ciphertext[1..17].try_into().unwrap();
        let kind: [u8; 4] = ciphertext[17..21].try_into().unwrap();
        if !matches!(&kind, b"CKL1" | b"CKI1") {
            return Err("分块类型无效".into());
        }
        let index = u32::from_be_bytes(ciphertext[21..25].try_into().unwrap());
        let total = u32::from_be_bytes(ciphertext[25..29].try_into().unwrap());
        let total_bytes = u64::from_be_bytes(ciphertext[29..37].try_into().unwrap());
        validate_chunk(
            &kind,
            index,
            total,
            total_bytes,
            ciphertext.len() - CHUNK_META_BYTES,
        )?;
        return Ok(Message::Chunk {
            id,
            kind,
            index,
            total,
            total_bytes,
            bytes: ciphertext[37..].to_vec(),
        });
    }
    if &header[..4] == b"CKI1" {
        return Ok(Message::Image(ciphertext));
    }
    let text = String::from_utf8(ciphertext).map_err(|_| "文本编码无效")?;
    if text.trim().is_empty() {
        return Err("空文本".into());
    }
    if &header[..4] == b"CKP1" {
        Ok(Message::Pair(text))
    } else {
        Ok(Message::Text(text))
    }
}

fn read_chunked(
    reader: &mut impl Read,
    key: &[u8; 32],
    first: Message,
) -> Result<([u8; 4], Vec<u8>), String> {
    let Message::Chunk {
        id,
        kind,
        index,
        total,
        total_bytes,
        bytes,
    } = first
    else {
        return Err("需要分块".into());
    };
    let limit = if &kind == b"CKL1" {
        MAX_TEXT_BYTES
    } else {
        MAX_IMAGE_BYTES
    };
    if index != 0 || total_bytes > limit as u64 {
        return Err("分块起始位置无效".into());
    }
    let mut output = Vec::with_capacity(total_bytes as usize);
    output.extend_from_slice(&bytes);
    for expected in 1..total {
        let next = read_message(reader, key)?;
        let Message::Chunk {
            id: next_id,
            kind: next_kind,
            index,
            total: next_total,
            total_bytes: next_bytes,
            bytes,
        } = next
        else {
            return Err("分块序列中断".into());
        };
        if next_id != id
            || next_kind != kind
            || index != expected
            || next_total != total
            || next_bytes != total_bytes
        {
            return Err("分块序列无效".into());
        }
        output.extend_from_slice(&bytes);
        if output.len() > total_bytes as usize {
            return Err("分块总长度超限".into());
        }
    }
    if output.len() != total_bytes as usize {
        return Err("分块总长度不匹配".into());
    }
    Ok((kind, output))
}

/// Normalize only after every authenticated chunk is present. Partial data never reaches storage.
pub(crate) fn assemble(
    reader: &mut impl Read,
    key: &[u8; 32],
    message: Message,
) -> Result<Message, String> {
    if !matches!(message, Message::Chunk { .. }) {
        return Ok(message);
    }
    let (kind, bytes) = read_chunked(reader, key, message)?;
    if &kind == b"CKI1" {
        return Ok(Message::Image(bytes));
    }
    let text = String::from_utf8(bytes).map_err(|_| "文本编码无效")?;
    if text.trim().is_empty() {
        return Err("空文本".into());
    }
    Ok(Message::Text(text))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hex(text: &str) -> Vec<u8> {
        text.as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect()
    }
    #[test]
    fn independent_probe_vectors_and_response_domain_separation() {
        let key = std::array::from_fn(|i| i as u8);
        let mut request = hex(
            "434b4831000102030405060708090a0b0000003089fa0a032d12a347bf8a35f89410006cd961a0f44561bbaefe8e35de69ddb8230a2c9c54999acd30f0c6eb5d4cdc7484",
        );
        let reply = hex(
            "434b5231000102030405060708090a0b0000003089fa0a032d12a347bf8a35f89410006cd961a0f44561bbaefe8e35de69ddb8235dbe86d7d3ae38edcdb1e4ce757de6a7",
        );
        assert_eq!(
            read_message(&mut request.as_slice(), &key).unwrap(),
            Message::Probe(key)
        );
        assert_eq!(
            read_message(&mut reply.as_slice(), &key).unwrap(),
            Message::ProbeReply(key)
        );
        request[2] = b'R';
        assert!(read_message(&mut request.as_slice(), &key).is_err());
        assert!(encrypt_payload(&key, b"CKH1", &[0; 31]).is_err());
    }

    #[test]
    fn independent_chunk_vector_and_strict_limits() {
        let key = std::array::from_fn(|i| i as u8);
        let wire = hex(
            "434b4331000102030405060708090a0b0000003f88fb09022a13a046b08b36f99311036dc633f9ab6074adb9e6972fc574c0a63cec802cf3ecfb391ed2afe128fa0c0feb19ae6a7e8a1e38df924e47613c2804",
        );
        let mut input = wire.as_slice();
        let first = read_message(&mut input, &key).unwrap();
        assert_eq!(
            assemble(&mut input, &key, first).unwrap(),
            Message::Text("分块😀".into())
        );
        assert_eq!(read_message(&mut hex("434b4132000102030405060708090a0b0000002089fa0a032d12a347bf8a35f89410006ccd9be81707f637cabe7534eb03f678c4").as_slice(), &key).unwrap(), Message::ChunkReceipt(std::array::from_fn(|i| i as u8)));
        let mut tampered = wire.clone();
        tampered[25] ^= 1;
        assert!(read_message(&mut tampered.as_slice(), &key).is_err());
        assert!(
            encrypt_chunk(
                &key,
                &[0; 16],
                b"CKL1",
                0,
                1,
                MAX_TEXT_BYTES as u64 + 1,
                b"x"
            )
            .is_err()
        );
        assert!(encrypt_chunk(&key, &[0; 16], b"CKL1", 0, u32::MAX, 1, b"x").is_err());
        assert!(encrypt_chunk(&key, &[0; 16], b"CKI1", 0, 1, 1, b"").is_err());
    }

    #[test]
    fn chunked_payload_roundtrips_and_rejects_reordered_chunks() {
        let key = std::array::from_fn(|i| i as u8);
        let id = [7u8; 16];
        let payload: Vec<u8> = (0..700_000).map(|i| (i % 251) as u8).collect();
        let total = payload.len().div_ceil(CHUNK_BYTES) as u32;
        let mut wire = Vec::new();
        let mut lengths = Vec::new();
        for index in 0..total {
            let start = index as usize * CHUNK_BYTES;
            let end = (start + CHUNK_BYTES).min(payload.len());
            let frame = encrypt_chunk(
                &key,
                &id,
                b"CKI1",
                index,
                total,
                payload.len() as u64,
                &payload[start..end],
            )
            .unwrap();
            lengths.push(frame.len());
            wire.extend_from_slice(&frame);
        }
        let first = read_message(&mut wire.as_slice(), &key).unwrap();
        let first_len = lengths[0];
        let mut rest = &wire[first_len..];
        let (_, decoded) = read_chunked(&mut rest, &key, first).unwrap();
        assert_eq!(decoded, payload);
        let second_start = first_len;
        let second_end = second_start + lengths[1];
        let mut reordered = Vec::new();
        reordered.extend_from_slice(&wire[..lengths[0]]);
        reordered.extend_from_slice(&wire[second_end..]);
        reordered.extend_from_slice(&wire[second_start..second_end]);
        let mut reordered_reader = Cursor::new(&reordered);
        let reordered_first = read_message(&mut reordered_reader, &key).unwrap();
        assert!(read_chunked(&mut reordered_reader, &key, reordered_first).is_err());
    }

    #[test]
    fn image_pair_and_tailnet_protocol() {
        let key = std::array::from_fn(|i| i as u8);
        let frame = hex(
            "434b4931000102030405060708090a0b0000005600ab4647241dbf4ab7833ffed1554a31c970b2e65174adb8ee912fc575dfb3f865802cf3eb57f5d9634aeabb9d5b4fd36184ac74fcd731022fa5d907904fd793373c128f85966249af9ed7198407482169e46e73fa01",
        );
        let Message::Image(bytes) = read_message(&mut frame.as_slice(), &key).unwrap() else {
            panic!("not image")
        };
        assert!(bytes.starts_with(b"\x89PNG"));
        let frame = encrypt_payload(&key, b"CKP1", br#"{"name":"phone","key":"key"}"#).unwrap();
        assert_eq!(
            read_message(&mut frame.as_slice(), &key).unwrap(),
            Message::Pair(r#"{"name":"phone","key":"key"}"#.into())
        );
        let mut wrong_type = frame.clone();
        wrong_type[..4].copy_from_slice(b"CKL1");
        assert!(read_message(&mut wrong_type.as_slice(), &key).is_err());
        for host in ["100.64.0.1", "100.127.255.254", "192.168.2.1"] {
            assert!(allowed_ip(host.parse().unwrap()));
        }
        for host in ["100.63.255.255", "100.128.0.1", "8.8.8.8"] {
            assert!(!allowed_ip(host.parse().unwrap()));
        }
        let mut large = frame;
        large[16..20].copy_from_slice(&1041u32.to_be_bytes());
        assert!(read_message(&mut large.as_slice(), &key).is_err());
    }

    #[test]
    fn accepts_independent_android_compatible_vector_and_rejects_corruption() {
        let key = std::array::from_fn(|index| index as u8);
        let mut frame = hex(
            "434b4c31000102030405060708090a0b00000033ea9761704272c030976782537db8b369881ed6953e1dc9990411bbe522a9c85883f75ffe2fc7da8685feab4ff3264fe82e7a86",
        );
        assert_eq!(
            read_text_frame(&mut frame.as_slice(), &key).unwrap(),
            "clipkeep 你好\nAndroid ↔ Windows"
        );
        let ack = hex("000102030405060708090a0bc6b04639412ed260990206761ffa37ccf928")
            .try_into()
            .unwrap();
        verify_ack(&key, &ack).unwrap();
        frame[20] ^= 1;
        assert!(read_text_frame(&mut frame.as_slice(), &key).is_err());
        let frame = encrypt_frame(&key, "测试").unwrap();
        assert_eq!(
            read_text_frame(&mut frame.as_slice(), &key).unwrap(),
            "测试"
        );
        verify_ack(&key, &make_ack(&key).unwrap()).unwrap();
    }
}
