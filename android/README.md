# clipkeep Android 0.3.0

Android 9（API 28）及以上。原生 Java、系统控件、SQLite，无 WebView 和生产环境第三方依赖。支持文本/图片历史、搜索、收藏、置顶、系统分享、图片选择、复制与局域网加密互传。

## 连接 Windows / Android

两端需要使用 0.3.0 或更新的客户端才能发现设备、双向配对和发送图片。0.2.0 的手动配对和文本协议继续兼容。

1. 在接收设备开启接收。Windows：设置 → 局域网传送；Android：设备 → 开启后台接收。
2. Android：设备 → 扫描响应设备。同一 Wi-Fi 自动通过 UDP 广播发现。
3. 选择设备，输入**对方设置页复制的完整配对码**，勾选“建立双向配对”。手机通过加密连接把自己的接收密钥交给对方；成功后双方都能选择彼此发送，无需再手动添加反向配对码。配对码应通过可信渠道传递；设备发现只返回名称、地址和能力，绝不广播配对密钥。
4. 长按记录 → 发送到设备。Windows 的条目右键菜单也可发送图片。收到后默认保存到历史；如需直接粘贴，请开启“收到后复制到系统剪贴板”，然后到目标应用手动粘贴。不会模拟点击或强行向正在编辑的窗口输入。

### Tailnet

手机和电脑分别安装并连接 Tailscale，使用各自的 **100.64.0.0/10 IPv4 地址**配对。也支持使用私网 IPv4 的其他 VPN。手机上的 Tailscale 必须允许 clipkeep 的流量进入 VPN；Tailnet ACL 和系统防火墙需允许 **TCP/UDP 45788**。本客户端不会创建或管理 VPN，也不需要 Tailscale 管理密钥。

Tailnet 不转发 Wi-Fi 广播，Android 也无法直接读取另一个应用中的 Tailscale 设备列表。因此在扫描框填入目标的 Tailscale IPv4 地址，多台用空格分隔（最多 32 台），点击扫描即可探测响应客户端；目标列表会保留。当前不支持自动枚举整个 Tailnet、MagicDNS 名称、IPv6 或公网地址。Wi-Fi AP 隔离、VPN 路由、ACL 或防火墙阻断时扫描可能为空，也可手动输入配对码。

### 后台与双向复制

- Android 后台接收使用用户主动开启的 `connectedDevice` 前台服务，显示**低优先级、无声常驻通知**，退到主屏幕不再停止接收。通知有“停止接收”按钮；应用内也可关闭。没有开机自启，也不获取无限 wakelock。
- Android → 设备：可主动“粘贴保存”、从其他应用分享文本/图片、选择图片，再发送。设备 → 复制与同步设置中可开启**前台监听**并选择自动发送目标，开关默认关闭。
- Windows → 设备：设置中可选择“本机复制后自动发送”的目标，默认关闭。连续复制时保留最新待发条目，避免大缓冲队列；失败不自动重试。
- 双方可独立开启“收到后复制”。已接收内容不会再次自动发送，避免回传循环。
- **Android 10+ 普通应用在后台无法持续读取其他应用的剪贴板**，所以不是完全后台自动双向同步。此版本不使用无障碍、ADB 提权、Root 或自定义输入法绕过限制。系统或厂商省电策略、强制停止、Doze、VPN 断开仍可能中断连接，必要时按设备设置允许后台运行。系统拒绝复制超长内容时，可从历史重新复制或使用设备发送。

## 图片与存储

- 图片输入：系统文件选择器、`image/*` 分享、剪贴板 content URI、加密网络收图。输出：PNG content URI 复制/分享、预览、加密发送。
- 单张图片编码最多 **8 MiB**，最多 **1600 万像素**。检查尺寸后才解码，不通过自动缩小原图隐式丢失内容。PNG 保持原字节，其他支持的格式转成 PNG；转码超过 8 MiB 会提示失败。文本仍为 UTF-8 **2 MiB** 上限。
- 图片文件放在应用私有目录，数据库只保存文件名和元数据；内容 URI 只读且按接收应用授权。不要把所有图片放入 SQLite BLOB 或列表内存。列表每页 80 条、文本预览 400 字，图片点开才加载缩放预览。
- 普通历史默认 50 MiB，收藏和置顶不自动删除；清理记录会回收不再引用的图片。v1 文本数据库自动迁移到 v2，保留原有文字和标记。系统云备份和设备迁移均不包含历史、配对码与密钥。
- 暂不支持桌面分组、`.ckbak` 备份导入/导出或跨设备完整数据库同步。

## 构建与签名

需要 JDK 17/21、Android SDK Platform 35、Build Tools 35.0.0。设置 `ANDROID_HOME` 或不提交的 `local.properties`。

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

正式签名：设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，执行 `./gradlew :app:assembleRelease :app:bundleRelease`。GitHub Release 工作流使用仓库 Secrets：`ANDROID_KEYSTORE_BASE64` 和后三个变量。工作流默认仅构建产物；维护者选择 publish 才创建 tag 和正式 release。

沿用 v0.2.0 正式证书，SHA-256：

```
1acac42eccdc8cd63b98d86a4fb5831e6637fbeabd71c81d8fecf6442c1385ee
```

使用 `apksigner verify --verbose --print-certs <APK>` 校验。正式版可覆盖同签名旧版；调试包签名不同，不能覆盖正式版。请勿提交密钥或口令。

## 验证

```sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug
```

设备测试会清空测试安装的数据，只应在专用模拟器运行。测试覆盖独立 Rust/Java 加密向量、Tailnet 地址边界、帧长度/类型篡改、旧数据库迁移、图片存储、接收确认和发现 nonce。

端到端测试需要安装 debug APK、启动 API 35 专用模拟器，并安装 Python `cryptography`。测试脚本会重置 debug 安装，不能用于真实历史数据：

```sh
python checks/e2e.py --reset-test-installation
```

`ADB` 可指定 adb 路径，`CLIPKEEP_TEST_DEVICE` 可指定 emulator 序号。脚本使用宿主机 TCP/UDP 45788 模拟对端，验证扫描→双向配对→退后台收文本/图→图片 URI 复制→反向加密发图→停止接收。真实 Tailscale VPN、厂商后台策略和 Android 9 真机仍需要实际设备验证。
