//! Opt-in text/image transfer and authenticated return pairing over LAN or Tailnet.
//! The receive code contains a random key; possession of that code authorizes sending.

#[cfg(test)]
use crate::lan_wire::read_text_frame;
use crate::lan_wire::{
    self, Message, allowed_ip, encrypt_frame, encrypt_payload, make_ack, read_message, verify_ack,
};
use crate::{Core, changed};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};

const PORT: u16 = 45788;
pub static SEND_WORK: Mutex<()> = Mutex::new(());
#[cfg(test)]
use crate::lan_wire::MAX_CIPHER_BYTES;
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
    #[serde(default)]
    copy_received: bool,
    #[serde(default)]
    auto_send: Option<String>,
    receive_key: String,
    peers: Vec<Peer>,
}

impl Config {
    fn fresh() -> Self {
        let mut key = [0u8; 32];
        getrandom::fill(&mut key).expect("Windows secure random source unavailable");
        Self {
            receive_enabled: false,
            copy_received: false,
            auto_send: None,
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
    pub copy_received: bool,
    pub auto_send: Option<String>,
    pub local_codes: Vec<LocalCode>,
    pub peers: Vec<PeerView>,
}

struct Worker {
    stop: Arc<AtomicBool>,
    join: JoinHandle<()>,
    discovery: JoinHandle<()>,
}

pub struct LanService {
    path: PathBuf,
    config: Mutex<Config>,
    operation: Mutex<()>,
    worker: Mutex<Option<Worker>>,
    sync_queue: Mutex<(bool, Option<String>)>,
}

fn decode_key(encoded: &str) -> Result<[u8; 32], String> {
    let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| "配对码无效")?;
    bytes.try_into().map_err(|_| "配对码无效".into())
}

fn parse_code(code: &str) -> Result<(String, String), String> {
    let body = code
        .trim()
        .strip_prefix(CODE_PREFIX)
        .ok_or("配对码格式无效")?;
    let (address, key) = body.split_once('/').ok_or("配对码格式无效")?;
    let address = address.to_ascii_lowercase();
    let (host, port) = address.split_once(':').ok_or("配对地址无效")?;
    if port != "45788" {
        return Err("配对端口无效".into());
    }
    crate::lan_address::validate_host(host)?;
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
            sync_queue: Mutex::new((false, None)),
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
            copy_received: config.copy_received,
            auto_send: config.auto_send.clone(),
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
        let udp = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, PORT))
            .map_err(|e| format!("无法监听发现端口：{e}"))?;
        udp.set_read_timeout(Some(Duration::from_millis(300)))
            .map_err(|e| e.to_string())?;
        let stop = Arc::new(AtomicBool::new(false));
        let discovery_stop = stop.clone();
        let discovery = thread::spawn(move || discovery_loop(udp, discovery_stop));
        let thread_stop = stop.clone();
        let app = app.clone();
        let join = thread::spawn(move || receive_loop(listener, key, app, thread_stop));
        *self.worker.lock().map_err(|e| e.to_string())? = Some(Worker {
            stop,
            join,
            discovery,
        });
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        if let Some(worker) = self.worker.lock().map_err(|e| e.to_string())?.take() {
            worker.stop.store(true, Ordering::SeqCst);
            let _ = worker.join.join();
            let _ = worker.discovery.join();
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

    pub fn set_auto_send(&self, address: Option<String>) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        if let Some(ref address) = address
            && !config.peers.iter().any(|p| &p.address == address)
        {
            return Err("设备未配对".into());
        }
        config.auto_send = address;
        save(&self.path, &config)?;
        *self.config.lock().map_err(|e| e.to_string())? = config;
        self.state()
    }
    // Latest clipboard wins during a burst. Queue stores a hash, never image/text buffers.
    pub fn queue_local(&self, app: &AppHandle, hash: String) {
        if !self.config.lock().is_ok_and(|c| c.auto_send.is_some()) {
            return;
        }
        let Ok(mut queue) = self.sync_queue.lock() else {
            return;
        };
        queue.1 = Some(hash);
        if queue.0 {
            return;
        }
        queue.0 = true;
        let app = app.clone();
        thread::spawn(move || {
            let core = app.state::<Core>();
            loop {
                let hash = {
                    let Ok(mut queue) = core.lan.sync_queue.lock() else {
                        return;
                    };
                    match queue.1.take() {
                        Some(hash) => hash,
                        None => {
                            queue.0 = false;
                            return;
                        }
                    }
                };
                let address = core
                    .lan
                    .config
                    .lock()
                    .ok()
                    .and_then(|c| c.auto_send.clone());
                let Some(address) = address else { continue };
                let id = core.conn().ok().and_then(|db| {
                    db.query_row("SELECT id FROM clips WHERE hash=?1", [&hash], |row| {
                        row.get::<_, i64>(0)
                    })
                    .ok()
                });
                if let Some(id) = id
                    && let Err(error) = crate::send_stored_clip(&core, id, &address)
                {
                    eprintln!("Clipboard sync failed: {error}");
                    let _ = app.emit("lan-sync-error", error);
                }
            }
        });
    }

    pub fn set_copy_received(&self, enabled: bool) -> Result<LanState, String> {
        let _guard = self.operation.lock().map_err(|e| e.to_string())?;
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        config.copy_received = enabled;
        save(&self.path, &config)?;
        *self.config.lock().map_err(|e| e.to_string())? = config;
        self.state()
    }

    pub fn add_peer(&self, name: &str, code: &str) -> Result<LanState, String> {
        let _guard = self
            .operation
            .try_lock()
            .map_err(|_| "设备设置正在更改，请重试配对")?;
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 40 {
            return Err("设备名称需为 1–40 个字符".into());
        }
        let (address, key) = parse_code(code)?;
        let mut config = self.config.lock().map_err(|e| e.to_string())?.clone();
        if let Some(peer) = config.peers.iter_mut().find(|p| p.address == address) {
            peer.name = name.to_owned();
            peer.key = key;
        } else {
            if config.peers.len() >= 32 {
                return Err("最多保存 32 台设备".into());
            }
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
        if config.auto_send.as_deref() == Some(address) {
            config.auto_send = None;
        }
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
        self.send(address, |key| encrypt_frame(key, text))
    }
    pub fn send_image(&self, address: &str, png: &[u8]) -> Result<(), String> {
        self.send(address, |key| encrypt_payload(key, b"CKI1", png))
    }
    fn send(
        &self,
        address: &str,
        frame: impl FnOnce(&[u8; 32]) -> Result<Vec<u8>, String>,
    ) -> Result<(), String> {
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
        let (target, _) = parse_code(&format!("{CODE_PREFIX}{}/{}", peer.address, peer.key))?;
        let target = crate::lan_address::resolve(&target)?;
        let mut stream = connect_retry(target)?;
        transfer(&mut stream, &key, &frame(&key)?)
    }
}

fn connect_retry(target: SocketAddr) -> Result<TcpStream, String> {
    for attempt in 0..3 {
        match TcpStream::connect_timeout(&target, Duration::from_millis(2500)) {
            Ok(stream) => return Ok(stream),
            Err(error) if attempt == 2 => return Err(format!("连接设备失败：{error}")),
            Err(_) => thread::sleep(Duration::from_millis(if attempt == 0 { 350 } else { 1000 })),
        }
    }
    unreachable!()
}

fn discovery_loop(socket: UdpSocket, stop: Arc<AtomicBool>) {
    let name = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "clipkeep Desktop".into());
    let name: String = name.chars().take(40).collect();
    let mut buffer = [0u8; 128];
    let mut budget = 20u8;
    let mut window = std::time::Instant::now();
    while !stop.load(Ordering::Relaxed) {
        let Ok((len, source)) = socket.recv_from(&mut buffer) else {
            continue;
        };
        if window.elapsed() >= Duration::from_secs(1) {
            budget = 20;
            window = std::time::Instant::now();
        }
        if budget == 0
            || !matches!(source.ip(), IpAddr::V4(ip) if allowed_ip(ip))
            || len != 38
            || &buffer[..6] != b"CKD1? "
        {
            continue;
        }
        if !buffer[6..38].iter().all(u8::is_ascii_hexdigit) {
            continue;
        }
        budget -= 1;
        let reply = serde_json::json!({"protocol":"CKD1", "nonce":std::str::from_utf8(&buffer[6..38]).unwrap(), "name":name, "port":PORT, "images":true, "pair":true, "probe":true});
        let _ = socket.send_to(reply.to_string().as_bytes(), source);
    }
}

#[cfg(test)]
fn send_stream(stream: &mut TcpStream, key: &[u8; 32], text: &str) -> Result<(), String> {
    transfer(stream, key, &encrypt_frame(key, text)?)
}

struct DeadlineIo<'a> {
    stream: &'a mut TcpStream,
    until: std::time::Instant,
}
impl<'a> DeadlineIo<'a> {
    fn new(stream: &'a mut TcpStream) -> Self {
        Self {
            stream,
            until: std::time::Instant::now() + Duration::from_secs(15),
        }
    }
    fn remaining(&self) -> std::io::Result<Duration> {
        self.until
            .checked_duration_since(std::time::Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::TimedOut, "传输超时"))
    }
}
impl Read for DeadlineIo<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
        self.stream
            .set_read_timeout(Some(self.remaining()?.min(Duration::from_secs(5))))?;
        self.stream.read(bytes)
    }
}
impl Write for DeadlineIo<'_> {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.stream
            .set_write_timeout(Some(self.remaining()?.min(Duration::from_secs(5))))?;
        self.stream.write(bytes)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.stream.flush()
    }
}

fn transfer(stream: &mut TcpStream, key: &[u8; 32], frame: &[u8]) -> Result<(), String> {
    let mut stream = DeadlineIo::new(stream);
    stream
        .write_all(frame)
        .map_err(|e| format!("发送失败：{e}"))?;
    let mut ack = [0u8; 30];
    stream
        .read_exact(&mut ack)
        .map_err(|e| format!("设备未确认接收：{e}"))?;
    verify_ack(key, &ack)
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

fn receive_one(stream: &mut TcpStream, key: &[u8; 32], app: &AppHandle) -> Result<(), String> {
    let core = app.state::<Core>();
    let copy_received = core
        .lan
        .config
        .lock()
        .map_err(|e| e.to_string())?
        .copy_received;
    let message = read_message(&mut DeadlineIo::new(stream), key)?;
    match message {
        Message::Probe(challenge) => {
            return stream
                .write_all(&encrypt_payload(key, b"CKR1", &challenge)?)
                .map_err(|e| e.to_string());
        }
        Message::ProbeReply(_) => return Err("非预期的探测响应".into()),
        Message::Text(text) => {
            if text.contains(CODE_PREFIX) {
                return Err("配对码不能进入历史".into());
            }
            core.save_text(&text, Some("局域网"))?;
            if copy_received && let Err(e) = crate::clipboard::write_text(&text) {
                eprintln!("Received text saved; clipboard write failed: {e}");
            }
        }
        Message::Image(png) => {
            let _work = crate::images::IMAGE_WORK
                .lock()
                .map_err(|e| e.to_string())?;
            let (w, h) = image::ImageReader::with_format(
                std::io::Cursor::new(&png),
                image::ImageFormat::Png,
            )
            .into_dimensions()
            .map_err(|e| e.to_string())?;
            if u64::from(w) * u64::from(h) > lan_wire::MAX_IMAGE_PIXELS {
                return Err("图片超过 1600 万像素".into());
            }
            let img = crate::images::decode_png(&png)?;
            let saved = core.images.write(&img, &crate::images::hash(&img))?;
            core.save_image(&saved, Some("局域网"))?;
            if copy_received && let Err(e) = crate::clipboard::write_image(&img) {
                eprintln!("Received image saved; clipboard write failed: {e}");
            }
        }
        Message::Pair(json) => {
            #[derive(Deserialize)]
            struct Pair {
                name: String,
                key: String,
            }
            let request: Pair = serde_json::from_str(&json).map_err(|e| e.to_string())?;
            let source = stream.peer_addr().map_err(|e| e.to_string())?.ip();
            core.lan.add_peer(
                &request.name,
                &format!("{CODE_PREFIX}{source}:{PORT}/{}", request.key),
            )?;
            let _ = app.emit("lan-state-changed", ());
        }
    }
    changed(app);
    stream.write_all(&make_ack(key)?).map_err(|e| e.to_string())
}

#[cfg(test)]
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
    fn discovery_returns_only_public_metadata() {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_millis(50)))
            .unwrap();
        let address = socket.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = stop.clone();
        let worker = thread::spawn(move || discovery_loop(socket, worker_stop));
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        client
            .send_to(b"CKD1? 0123456789abcdef0123456789abcdef", address)
            .unwrap();
        let mut reply = [0u8; 1024];
        let (len, _) = client.recv_from(&mut reply).unwrap();
        stop.store(true, Ordering::SeqCst);
        worker.join().unwrap();
        let json: serde_json::Value = serde_json::from_slice(&reply[..len]).unwrap();
        assert_eq!(json["nonce"], "0123456789abcdef0123456789abcdef");
        assert_eq!(json["protocol"], "CKD1");
        assert_eq!(json["port"], PORT);
        assert!(json.get("key").is_none());
        assert!(json.get("code").is_none());
    }
    #[test]
    fn sync_settings_persist_and_pairing_never_blocks_receiver_shutdown() {
        let dir = tempfile::tempdir().unwrap();
        let service = LanService::new(dir.path());
        let code = format!(
            "{CODE_PREFIX}100.64.0.2:{PORT}/{}",
            service.config.lock().unwrap().receive_key
        );
        service.add_peer("phone", &code).unwrap();
        service
            .set_auto_send(Some(format!("100.64.0.2:{PORT}")))
            .unwrap();
        service.set_copy_received(true).unwrap();
        let state = LanService::new(dir.path()).state().unwrap();
        assert!(state.copy_received);
        assert!(state.auto_send.is_some());
        let guard = service.operation.lock().unwrap();
        assert!(service.add_peer("phone", &code).is_err());
        drop(guard);
        service.remove_peer(&format!("100.64.0.2:{PORT}")).unwrap();
        assert!(service.state().unwrap().auto_send.is_none());
    }

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
