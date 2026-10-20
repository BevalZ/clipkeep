## clipkeep v0.3.0

- Android 新增图片导入、收发、预览、复制和分享；Windows 设备发送支持图片。PNG 最多 8 MiB、1600 万像素，文本最多 2 MiB。
- 支持 Tailscale 100.64.0.0/10 IPv4 地址。Android 可广播发现同 Wi-Fi 的响应客户端，并探测输入的 Tailnet 地址列表。
- Android 扫描选择设备后，输入接收方配对码即可建立加密双向配对。发现响应不包含密钥；0.2.0 的手动文本传输仍兼容。
- Android 新增无声常驻通知的后台接收服务，离开应用后继续接收；双方可选择收到后写入剪贴板。Windows 可选择自动发送本机复制内容，Android 提供前台监听与指定设备自动发送。
- 图片私有文件存储、只读 URI 授权、分页列表，旧文本数据库原位迁移。

## 安装包

Windows x64 提供 `windows-x64-setup.exe` 和 `windows-x64-portable.exe`；Android 9+ 提供正式签名 `android.apk` 与商店用 `android.aab`。两端需升级到 0.3.0 才能使用图片和设备发现。

安卓沿用 v0.2.0 密钥，可覆盖同签名旧版本。证书 SHA-256：

```
1acac42eccdc8cd63b98d86a4fb5831e6637fbeabd71c81d8fecf6442c1385ee
```

## 使用边界

- Android 10+ 不允许普通应用持续读取后台剪贴板。后台可接收，主动分享/粘贴或应用前台监听可发送；不是完全后台自动双向同步。前台服务有常驻通知，厂商省电策略或 Doze 仍可能限制连接。
- Tailnet 需设备各自运行 Tailscale，ACL 和防火墙允许 TCP/UDP 45788。不自动枚举 Tailscale 的设备列表：Wi-Fi 自动广播，Tailnet 用输入并记住的 IPv4 目标探测；暂不支持 MagicDNS 或 IPv6。
- 配对码授权对方传送内容并建立回向配对，请通过可信渠道传递。收到后复制与自动发送均为可选开关，不会模拟按键粘贴到正在编辑的窗口。
- Windows 没有 Authenticode 签名。Android 已验证 API 35 模拟器链路；真实 Tailnet、多厂商后台策略、API 28 真机和 Windows GUI 仍需实际设备验证。
