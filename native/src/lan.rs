//! Small, opt-in, one-way text transfer over the local network.
//! The receive code contains a random key; possession of that code authorizes sending.

use crate::{Core, changed, clipboard};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use chacha20poly1305::{
    ChaCha20Poly1305, Key, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};

const PORT: u16 = 45788;
const MAGIC: &[u8; 4] = b"CKL1";
const AAD: &[u8] = b"clipkeep-lan-text-v1";
const ACK_AAD: &[u8] = b"clipkeep-lan-ack-v1";
const MAX_CIPHER_BYTES: usize = clipboard::MAX_TEXT_BYTES + 16;
const CODE_PREFIX: &str = "cklan1://";

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Peer {
    name: String,
    address: String,
    key: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Config {
    receive_enabled: bool,
    receive_key: String,
    peers: Vec<Peer>,
}

impl Config {
    fn fresh() -> Self {
        let mut key = [0u8; 32];
        getrandom::fill(&mut key).expect("Windows secure random source unavailable");
        Self {
            receive_enabled: false,
            receive_key: URL_SAFE_NO_PAD.encode(key),
            peers: Vec::new(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerView {
    pub name: String,
    pub address: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalCode {
    pub address: String,
    pub code: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanState {
    pub receive_enabled: bool,
    pub local_codes: Vec<LocalCode>,
    pub peers: Vec<PeerView>,
}

struct Worker {
    stop: Arc<AtomicBool>,
    join: JoinHandle<()>,
}

pub struct LanService {
    path: PathBuf,
    config: Mutex<Config>,
    operation: Mutex<()>,
    worker: Mutex<Option<Worker>>,
}

fn decode_key(encoded: &str) -> Result<[u8; 32], String> {
    let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| "配对码无效")?;
    bytes.try_into().map_err(|_| "配对码无效".into())
}

fn allowed_ip(ip: Ipv4Addr) -> bool {
    ip.is_private() || ip.is_link_local() || ip.is_loopback()
}

fn parse_code(code: &str) -> Result<(SocketAddrV4, String), String> {
    let body = code
        .trim()
        .strip_prefix(CODE_PREFIX)
        .ok_or("配对码格式无效")?;
    let (address, key) = body.split_once('/').ok_or("配对码格式无效")?;
    let address: SocketAddrV4 = address.parse().map_err(|_| "配对地址无效")?;
    if !allowed_ip(*address.ip()) || address.port() != PORT {
        return Err("仅支持本地网络 IPv4 地址和默认端口".into());
    }
    decode_key(key)?;
    Ok((address, key.to_owned()))
}

fn save(path: &Path, config: &Config) -> Result<(), String> {
    let json = serde_json::to_vec_pretty(config).map_err(|e| e.to_string())?;
    crate::atomic_file::save(path, &json).map_err(|e| format!("保存局域网设置失败：{e}"))
}

impl LanService {
    pub fn new(data_dir: &Path) -> Self {
        let path = data_dir.join("lan.json");
        let config = std::fs::read(&path)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<Config>(&bytes).ok())
            .filter(|c| decode_key(&c.receive_key).is_ok())
            .unwrap_or_else(|| {
                if path.exists() {
                    let _ = std::fs::copy(&path, path.with_extension("json.bad"));
                }
                let c = Config::fresh();
                if let Err(e) = save(&path, &c) {
                    eprintln!("{e}");
                }
                c
            });
        Self {
            path,
            config: Mutex::new(config),
            operation: Mutex::new(()),
            worker: Mutex::new(None),
        }
    }

    pub fn state(&self) -> Result<LanState, String> {
        let config = self.config.lock().map_err(|e| e.to_string())?.clone();
        let mut ips: Vec<Ipv4Addr> = local_ip_address::list_afinet_netifas()
            .unwrap_or_default()
            .into_iter()
            .filter_map(|(_, ip)| match ip {
                IpAddr::V4(ip) if allowed_ip(ip) && !ip.is_loopback() => Some(ip),
                _ => None,
            })
            .collect();
        ips.sort_unstable();
        ips.dedup();
        Ok(LanState {
            receive_enabled: config.receive_enabled,
            local_codes: ips
                .into_iter()
                .map(|ip| {
                    let address = SocketAddrV4::new(ip, PORT).to_string();
                    LocalCode {
                        code: format!("{CODE_PREFIX}{address}/{}", config.receive_key),
                        address,
                    }
                })
                .collect(),
            peers: config
                .peers
                .iter()
                .map(|p| PeerView {
                    name: p.name.clone(),
                    address: p.address.clone(),
                })
                .collect(),
        })
    }

    pub fn restore(&self, app: &AppHandle) {
        let enabled = self.config.lock().is_ok_and(|c| c.receive_enabled);
        if enabled && let Err(e) = self.start(app) {
            eprintln!("LAN receiver could not start: {e}");
            if let Ok(mut c) = self.config.lock() {
                c.receive_enabled = false;
                let _ = save(&self.path, &c);
            }
        }
    }

    fn start(&self, app: &AppHandle) -> Result<(), String> {
        let key = decode_key(&self.config.lock().map_err(|e| e.to_string())?.receive_key)?;
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, PORT))
            .map_err(|e| format!("无法监听端口 {PORT}：{e}"))?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = stop.clone();
        let app = app.clone();
        let join = thread::spawn(move || receive_loop(listener, key, app, thread_stop));
        *self.worker.lock().map_err(|e| e.to_string())? = Some(Worker { stop, join });
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        if let Some(worker) = self.worker.lock().map_err(|e| e.to_string())?.take() {
            worker.stop.store(true, Ordering::SeqCst);
            let _ = worker.join.join();
        }
        Ok(())
    }

    pub fn set_receive(&self, app: &AppHandle, enabled: bool) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let mut next = self.config.lock().map_err(|e| e.to_string())?.clone();
        if next.receive_enabled != enabled {
            if enabled {
                self.start(app)?;
            } else {
                self.stop()?;
            }
            next.receive_enabled = enabled;
            if let Err(e) = save(&self.path, &next) {
                if enabled {
                    let _ = self.stop();
                } else {
                    let _ = self.start(app);
                }
                return Err(e);
            }
            *self.config.lock().map_err(|e| e.to_string())? = next;
            let _ = app.emit("lan-state-changed", ());
        }
        self.state()
    }

    pub fn add_peer(&self, name: &str, code: &str) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 40 {
            return Err("设备名称需为 1–40 个字符".into());
        }
        let (address, key) = parse_code(code)?;
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        let address = address.to_string();
        if let Some(peer) = config.peers.iter_mut().find(|p| p.address == address) {
            peer.name = name.to_owned();
            peer.key = key;
        } else {
            config.peers.push(Peer {
                name: name.to_owned(),
                address,
                key,
            });
        }
        save(&self.path, &config)?;
        *self.config.lock().map_err(|e| e.to_string())? = config;
        self.state()
    }

    pub fn remove_peer(&self, address: &str) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        config.peers.retain(|p| p.address != address);
        save(&self.path, &config)?;
        *self.config.lock().map_err(|e| e.to_string())? = config;
        self.state()
    }

    pub fn rotate_code(&self, app: &AppHandle) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let mut new_key = [0u8; 32];
        getrandom::fill(&mut new_key).map_err(|e| e.to_string())?;
        let enabled = self
            .config
            .lock()
            .map_err(|e| e.to_string())?
            .receive_enabled;
        if enabled {
            self.stop()?;
        }
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        config.receive_key = URL_SAFE_NO_PAD.encode(new_key);
        if let Err(e) = save(&self.path, &config) {
            if enabled {
                let _ = self.start(app);
            }
            return Err(e);
        }
        *self.config.lock().map_err(|e| e.to_string())? = config;
        if enabled && let Err(e) = self.start(app) {
            if let Ok(mut current) = self.config.lock() {
                current.receive_enabled = false;
                let _ = save(&self.path, &current);
            }
            return Err(e);
        }
        self.state()
    }

    pub fn send_text(&self, address: &str, text: &str) -> Result<(), String> {
        if text.trim().is_empty() || text.len() > clipboard::MAX_TEXT_BYTES {
            return Err("只能传送不超过 2 MB 的非空文本".into());
        }
        let peer = self
            .config
            .lock()
            .map_err(|e| e.to_string())?
            .peers
            .iter()
            .find(|p| p.address == address)
            .cloned()
            .ok_or("设备未配对")?;
        let key = decode_key(&peer.key)?;
        let target: SocketAddr = peer.address.parse().map_err(|_| "设备地址无效")?;
        if !matches!(target, SocketAddr::V4(a) if allowed_ip(*a.ip()) && a.port() == PORT) {
            return Err("仅支持本地网络 IPv4 地址和默认端口".into());
        }
        let mut stream = TcpStream::connect_timeout(&target, Duration::from_secs(3))
            .map_err(|e| format!("连接设备失败：{e}"))?;
        send_stream(&mut stream, &key, text)
    }
}

fn send_stream(stream: &mut TcpStream, key: &[u8; 32], text: &str) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| e.to_string())?;
    stream
        .set_write_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| e.to_string())?;
    let frame = encrypt_frame(key, text)?;
    stream
        .write_all(&frame)
        .map_err(|e| format!("发送失败：{e}"))?;
    let mut ack = [0u8; 30];
    stream
        .read_exact(&mut ack)
        .map_err(|e| format!("设备未确认接收：{e}"))?;
    verify_ack(key, &ack)
}

fn encrypt_frame(key: &[u8; 32], text: &str) -> Result<Vec<u8>, String> {
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|e| e.to_string())?;
    let cipher = ChaCha20Poly1305::new(&Key::from(*key));
    let ciphertext = cipher
        .encrypt(
            &Nonce::from(nonce),
            Payload {
                msg: text.as_bytes(),
                aad: AAD,
            },
        )
        .map_err(|_| "加密失败")?;
    let mut frame = Vec::with_capacity(20 + ciphertext.len());
    frame.extend_from_slice(MAGIC);
    frame.extend_from_slice(&nonce);
    frame.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
    frame.extend_from_slice(&ciphertext);
    Ok(frame)
}

fn make_ack(key: &[u8; 32]) -> Result<[u8; 30], String> {
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

fn verify_ack(key: &[u8; 32], ack: &[u8; 30]) -> Result<(), String> {
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

fn receive_loop(listener: TcpListener, key: [u8; 32], app: AppHandle, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((mut stream, addr)) => {
                let allowed = matches!(addr.ip(), IpAddr::V4(ip) if allowed_ip(ip));
                if !allowed {
                    continue;
                }
                let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
                if let Err(e) = receive_one(&mut stream, &key, &app) {
                    eprintln!("LAN receive rejected: {e}");
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(40))
            }
            Err(e) => {
                eprintln!("LAN listener error: {e}");
                thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

fn read_text_frame(reader: &mut impl Read, key: &[u8; 32]) -> Result<String, String> {
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
    let plain = cipher
        .decrypt(
            &Nonce::from(nonce),
            Payload {
                msg: &ciphertext,
                aad: AAD,
            },
        )
        .map_err(|_| "配对码不匹配或数据损坏")?;
    let text = String::from_utf8(plain).map_err(|_| "文本编码无效")?;
    if text.trim().is_empty() {
        return Err("空文本".into());
    }
    Ok(text)
}

fn receive_one(stream: &mut TcpStream, key: &[u8; 32], app: &AppHandle) -> Result<(), String> {
    receive_stream(stream, key, |text| {
        let core = app.state::<Core>();
        core.save_text(text, Some("局域网"))?;
        changed(app);
        Ok(())
    })
}

fn receive_stream(
    stream: &mut TcpStream,
    key: &[u8; 32],
    save_text: impl FnOnce(&str) -> Result<(), String>,
) -> Result<(), String> {
    let text = read_text_frame(stream, key)?;
    save_text(&text)?;
    stream
        .write_all(&make_ack(key)?)
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_and_encryption_roundtrip() {
        let c = Config::fresh();
        let key = decode_key(&c.receive_key).unwrap();
        let code = format!("{CODE_PREFIX}192.168.1.2:{PORT}/{}", c.receive_key);
        assert!(parse_code(&code).is_ok());
        assert!(parse_code(&code.replace("192.168.1.2", "8.8.8.8")).is_err());
        let frame = encrypt_frame(&key, "你好\nlong text").unwrap();
        assert_eq!(
            read_text_frame(&mut frame.as_slice(), &key).unwrap(),
            "你好\nlong text"
        );
        let mut tampered = frame.clone();
        tampered[20] ^= 1;
        assert!(read_text_frame(&mut tampered.as_slice(), &key).is_err());
        let mut wrong_key = key;
        wrong_key[0] ^= 1;
        assert!(read_text_frame(&mut frame.as_slice(), &wrong_key).is_err());
        let mut oversized = frame.clone();
        oversized[16..20].copy_from_slice(&((MAX_CIPHER_BYTES + 1) as u32).to_be_bytes());
        assert!(
            read_text_frame(&mut oversized.as_slice(), &key)
                .unwrap_err()
                .contains("超限")
        );
        let ack = make_ack(&key).unwrap();
        verify_ack(&key, &ack).unwrap();
        let mut forged = ack;
        forged[29] ^= 1;
        assert!(verify_ack(&key, &forged).is_err());
    }

    #[test]
    fn pairing_persists_with_receiver_off_by_default() {
        let mut random = [0u8; 8];
        getrandom::fill(&mut random).unwrap();
        let dir =
            std::env::temp_dir().join(format!("clipkeep-lan-test-{}", u64::from_ne_bytes(random)));
        std::fs::create_dir(&dir).unwrap();
        let service = LanService::new(&dir);
        assert!(!service.state().unwrap().receive_enabled);
        let key = service.config.lock().unwrap().receive_key.clone();
        let code = format!("{CODE_PREFIX}127.0.0.1:{PORT}/{key}");
        service.add_peer("测试设备", &code).unwrap();
        drop(service);
        let reloaded = LanService::new(&dir);
        let state = reloaded.state().unwrap();
        assert!(!state.receive_enabled);
        assert_eq!(state.peers.len(), 1);
        assert_eq!(state.peers[0].name, "测试设备");
        drop(reloaded);
        std::fs::remove_file(dir.join("lan.json")).unwrap();
        std::fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn socket_send_requires_receiver_confirmation() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let key = decode_key(&Config::fresh().receive_key).unwrap();
        let receiver = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let conn = crate::db::open(std::path::Path::new(":memory:")).unwrap();
            receive_stream(&mut stream, &key, |text| {
                let clip = crate::db::NewClip {
                    kind: crate::db::KIND_TEXT,
                    content: text,
                    hash: &crate::db::text_hash(text),
                    width: None,
                    height: None,
                    size: text.len() as i64,
                    source: Some("局域网"),
                };
                crate::db::upsert(&conn, &clip, 0).map_err(|e| e.to_string())?;
                Ok(())
            })
            .unwrap();
            let clips = crate::db::list(&conn, crate::db::Scope::All, "").unwrap();
            assert_eq!(clips.len(), 1);
            let (_, text) = crate::db::get(&conn, clips[0].id).unwrap().unwrap();
            (text, clips[0].source.clone())
        });
        let mut sender = TcpStream::connect(address).unwrap();
        send_stream(&mut sender, &key, "本机回环测试\n第二行").unwrap();
        assert_eq!(
            receiver.join().unwrap(),
            (
                "本机回环测试\n第二行".to_string(),
                Some("局域网".to_string())
            )
        );
    }

    #[test]
    fn socket_send_rejects_forged_confirmation() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let key = decode_key(&Config::fresh().receive_key).unwrap();
        let receiver = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            read_text_frame(&mut stream, &key).unwrap();
            let mut ack = make_ack(&key).unwrap();
            ack[29] ^= 1;
            stream.write_all(&ack).unwrap();
        });
        let mut sender = TcpStream::connect(address).unwrap();
        assert!(send_stream(&mut sender, &key, "必须确认").is_err());
        receiver.join().unwrap();
    }

    #[test]
    fn socket_send_fails_when_receiver_cannot_save() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let key = decode_key(&Config::fresh().receive_key).unwrap();
        let receiver = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            receive_stream(&mut stream, &key, |_| Err("存储失败".into())).unwrap_err()
        });
        let mut sender = TcpStream::connect(address).unwrap();
        assert!(send_stream(&mut sender, &key, "不能误报成功").is_err());
        assert_eq!(receiver.join().unwrap(), "存储失败");
    }
}
