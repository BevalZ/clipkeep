//! Validate names without DNS; resolve only on the transfer worker, with a bounded wait.
use crate::lan_wire::allowed_ip;
use std::net::{Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::sync::{OnceLock, mpsc};
use std::time::Duration;

pub(crate) fn validate_host(host: &str) -> Result<(), String> {
    if host.bytes().all(|c| c.is_ascii_digit() || c == b'.') {
        let ip: Ipv4Addr = host.parse().map_err(|_| "IPv4 地址无效")?;
        return if allowed_ip(ip) {
            Ok(())
        } else {
            Err("仅允许私网或 Tailnet IPv4".into())
        };
    }
    if host.is_empty() || host.len() > 253 || (host.contains('.') && !host.ends_with(".ts.net")) {
        return Err("请输入 MagicDNS 短名称或 .ts.net 完整名称".into());
    }
    if host.split('.').any(|label| {
        label.is_empty()
            || label.len() > 63
            || !label.as_bytes()[0].is_ascii_alphanumeric()
            || !label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
            || !label
                .bytes()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
    }) {
        return Err("MagicDNS 名称无效".into());
    }
    Ok(())
}

type Reply = mpsc::SyncSender<Result<Vec<SocketAddr>, String>>;
static DNS: OnceLock<mpsc::SyncSender<(String, Reply)>> = OnceLock::new();

pub(crate) fn resolve(address: &str) -> Result<SocketAddr, String> {
    let (host, port) = address.rsplit_once(':').ok_or("配对地址无效")?;
    validate_host(host)?;
    if port != "45788" {
        return Err("配对端口无效".into());
    }
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(SocketAddr::from((ip, 45788)));
    }
    // One resolver thread and one queued request even if the system resolver stalls.
    let dns = DNS.get_or_init(|| {
        let (tx, rx) = mpsc::sync_channel::<(String, Reply)>(1);
        std::thread::spawn(move || {
            while let Ok((address, reply)) = rx.recv() {
                let _ = reply.send(
                    address
                        .to_socket_addrs()
                        .map(|v| v.collect())
                        .map_err(|_| "无法解析设备名称，请检查 MagicDNS".into()),
                );
            }
        });
        tx
    });
    let (tx, rx) = mpsc::sync_channel(1);
    dns.try_send((address.to_owned(), tx))
        .map_err(|_| "DNS 查询尚未结束，请稍后重试")?;
    let addresses = rx
        .recv_timeout(Duration::from_secs(3))
        .map_err(|_| "DNS 查询超时，请检查 Tailscale / MagicDNS")??;
    restricted_address(&addresses)
}

fn restricted_address(addresses: &[SocketAddr]) -> Result<SocketAddr, String> {
    addresses
        .iter()
        .find(|address| matches!(address, SocketAddr::V4(v4) if allowed_ip(*v4.ip())))
        .copied()
        .ok_or_else(|| "名称未解析到私网或 Tailnet IPv4".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_names_without_dns_and_filters_resolved_addresses() {
        for host in [
            "desktop",
            "desktop-1.tail123.ts.net",
            "100.64.0.1",
            "192.168.1.2",
        ] {
            assert!(validate_host(host).is_ok(), "{host}");
        }
        for host in [
            "",
            "example.com",
            "x.ts.net.evil.com",
            "a..ts.net",
            "-bad",
            "bad-",
            "8.8.8.8",
            "192.168.01.2",
            "127.1",
            "[::1]",
            "host/path",
        ] {
            assert!(validate_host(host).is_err(), "{host}");
        }
        assert!(
            restricted_address(&[
                "8.8.8.8:45788".parse().unwrap(),
                "[::1]:45788".parse().unwrap()
            ])
            .is_err()
        );
        assert_eq!(
            restricted_address(&[
                "8.8.8.8:45788".parse().unwrap(),
                "100.65.1.2:45788".parse().unwrap()
            ])
            .unwrap(),
            "100.65.1.2:45788".parse::<SocketAddr>().unwrap()
        );
        assert_eq!(
            resolve("127.0.0.1:45788").unwrap(),
            "127.0.0.1:45788".parse::<SocketAddr>().unwrap()
        );
    }
}
