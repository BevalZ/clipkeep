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
pub(crate) const MAX_CIPHER_BYTES: usize = MAX_TEXT_BYTES + 16;
pub(crate) fn encrypt_frame(key: &[u8; 32], text: &str) -> Result<Vec<u8>, String> {
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    if text.len() > MAX_TEXT_BYTES || text.trim().is_empty() {
        return Err("文本为空或长度超限".into());
    }
    let mut frame = Vec::with_capacity(20 + text.len() + 16);
    frame.extend_from_slice(MAGIC);
    frame.extend_from_slice(&nonce);
    frame.extend_from_slice(&((text.len() + 16) as u32).to_be_bytes());
    frame.extend_from_slice(text.as_bytes());
    let tag = cipher
        .encrypt_inout_detached(&Nonce::from(nonce), AAD, (&mut frame[20..]).into())
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

pub(crate) fn read_text_frame(reader: &mut impl Read, key: &[u8; 32]) -> Result<String, String> {
    let mut header = [0u8; 20];
    reader.read_exact(&mut header).map_err(|e| e.to_string())?;
    if &header[..4] != MAGIC {
        return Err("协议版本不匹配".into());
    }
    let len = u32::from_be_bytes(header[16..20].try_into().unwrap()) as usize;
    if !(16..=MAX_CIPHER_BYTES).contains(&len) {
        return Err("文本长度超限".into());
    }
    let mut ciphertext = vec![0u8; len];
    reader
        .read_exact(&mut ciphertext)
        .map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let nonce: [u8; 12] = header[4..16].try_into().unwrap();
    cipher
        .decrypt_in_place(&Nonce::from(nonce), AAD, &mut ciphertext)
        .map_err(|_| "配对码不匹配或数据损坏")?;
    let text = String::from_utf8(ciphertext).map_err(|_| "文本编码无效")?;
    if text.trim().is_empty() {
        return Err("空文本".into());
    }
    Ok(text)
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
