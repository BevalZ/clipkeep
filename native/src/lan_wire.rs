//! Shared wire format, kept independent of the desktop runtime for compatibility tests.
use chacha20poly1305::{
    ChaCha20Poly1305, Key, KeyInit, Nonce,
    aead::{Aead, AeadInOut, Payload},
};
use std::io::Read;
const MAGIC: &[u8; 4] = b"CKL1";
const AAD: &[u8] = b"clipkeep-lan-text-v1";
const ACK_AAD: &[u8] = b"clipkeep-lan-ack-v1";
pub(crate) const MAX_TEXT_BYTES: usize = 2 * 1024 * 1024;
#[cfg(test)]
pub(crate) const MAX_CIPHER_BYTES: usize = MAX_TEXT_BYTES + 16;
pub(crate) const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const MAX_IMAGE_PIXELS: u64 = 16_000_000;
#[derive(Debug, PartialEq)]
pub(crate) enum Message {
    Text(String),
    Image(Vec<u8>),
    Pair(String),
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
    if bytes.is_empty() || bytes.len() > limit {
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
