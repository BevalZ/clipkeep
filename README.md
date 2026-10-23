<p align="center">
  <img src="assets/brand/clipkeep-512.png" width="96" alt="clipkeep 图标">
</p>

<p align="center">
    <a href="https://linux.do" alt="LINUX DO">
        <img
            src="https://img.shields.io/badge/LINUX-DO-FFB003.svg?logo=data:image/svg%2bxml;base64,DQo8c3ZnIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiPjxwYXRoIGQ9Ik00Ni44Mi0uMDU1aDYuMjVxMjMuOTY5IDIuMDYyIDM4IDIxLjQyNmM1LjI1OCA3LjY3NiA4LjIxNSAxNi4xNTYgOC44NzUgMjUuNDV2Ni4yNXEtMi4wNjQgMjMuOTY4LTIxLjQzIDM4LTExLjUxMiA3Ljg4NS0yNS40NDUgOC44NzRoLTYuMjVxLTIzLjk3LTIuMDY0LTM4LjAwNC0yMS40M1EuOTcxIDY3LjA1Ni0uMDU0IDUzLjE4di02LjQ3M0MxLjM2MiAzMC43ODEgOC41MDMgMTguMTQ4IDIxLjM3IDguODE3IDI5LjA0NyAzLjU2MiAzNy41MjcuNjA0IDQ2LjgyMS0uMDU2IiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZWNlY2VjO2ZpbGwtb3BhY2l0eToxIi8+PHBhdGggZD0iTTQ3LjI2NiAyLjk1N3EyMi41My0uNjUgMzcuNzc3IDE1LjczOGE0OS43IDQ5LjcgMCAwIDEgNi44NjcgMTAuMTU3cS00MS45NjQuMjIyLTgzLjkzIDAgOS43NS0xOC42MTYgMzAuMDI0LTI0LjM4N2E2MSA2MSAwIDAgMSA5LjI2Mi0xLjUwOCIgc3R5bGU9InN0cm9rZTpub25lO2ZpbGwtcnVsZTpldmVub2RkO2ZpbGw6IzE5MTkxOTtmaWxsLW9wYWNpdHk6MSIvPjxwYXRoIGQ9Ik03Ljk4IDcwLjkyNmMyNy45NzctLjAzNSA1NS45NTQgMCA4My45My4xMTNRODMuNDI2IDg3LjQ3MyA2Ni4xMyA5NC4wODZxLTE4LjgxIDYuNTQ0LTM2LjgzMi0xLjg5OC0xNC4yMDMtNy4wOS0yMS4zMTctMjEuMjYyIiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZjlhZjAwO2ZpbGwtb3BhY2l0eToxIi8+PC9zdmc+" /></a>
</p>

# clipkeep：Windows 剪贴板历史与轻量 Android 客户端

> Windows 桌面端采用 Rust + Tauri 2；Android 采用原生 Java。支持本地历史、搜索和局域网加密文本互传。MIT 开源。

新增 [原生 Android 客户端](android/README.md)，支持文本/图片、收藏、搜索、后台接收及与桌面端加密互传。本次 [内存优化与测量](MEMORY.md)覆盖备份导入、列表、托盘页面和图片处理。

![浅色和深色主界面](assets/screenshots/hero.png)

---

## 为什么要再写一个

Windows 自带的 Win+V 我用了很久，问题大家应该都遇到过：

- 只存 **25 条**
- **重启就清空**
- **不能搜索**

开源的替代品我基本都试过。Ditto 和 CopyQ 很强，但界面停在十年前，设置项多到劝退；EcoPaste 和 PasteBar 界面好看了，可要装安装包，页面卡顿响应慢，也没法在两台电脑之间传东西。

我想要的其实很简单：**下载一个 exe 双击就能用，Win+V 照按，记录永远不丢，另一台电脑要用的时候直接发过去。** 找不到，就自己写了。

---

## 和主流开源方案对比

|  | **clipkeep** | Ditto | CopyQ | EcoPaste | PasteBar |
|---|---|---|---|---|---|
| **技术栈** | **Rust + Tauri 2 + Vue 3** | C++ / MFC | C++ / Qt | Rust + Tauri 2 + React | Rust + Tauri + React |
| **分发方式** | **Windows 便携 exe / 各平台安装包** | 安装包 / 便携目录 | 安装包 / 便携目录 | 安装包 | 安装包 |
| **局域网互传** | **✅ 内置，加密直连，免账号** | 需手动配置 IP | ❌ | ❌ | ❌ |
| **界面** | **现代，跟随系统深浅色** | 老式 Win32 | Qt 传统风格 | 现代 | 现代 |
| **往管理员窗口粘贴** | **✅ 开箱即用** | 需自行提权运行 | 需自行提权运行 | 需自行提权运行 | 需自行提权运行 |
| **开源协议** | **MIT** | GPL-3.0 | GPL-3.0 | Apache-2.0 | CC BY-NC（禁止商用） |

**一句话总结：** 轻量、免安装、界面现代，同时自带局域网加密互传。目前这几样能同时满足的，只有 clipkeep。

> 桌面版支持文字和图片；资源管理器图片文件捕获、管理员窗口粘贴为 Windows 功能。Android 支持文本和图片历史、扫描配对、Wi-Fi / Tailnet 加密互传和后台接收，暂不支持分组或桌面备份。富文本和任意文件尚不支持。

---

## 功能一览

**📋 记录**
- 历史不限条数，只按容量清理（默认 500 MB，可设为不限）
- **收藏、置顶、分组里的内容永不被自动清理**
- 文字和图片都记录，资源管理器里复制图片文件也能记下来
- blake3 哈希去重，复制同样的内容不会刷屏

![搜索、右键菜单、全文预览和编辑](assets/screenshots/main.png)

**🔍 查找和整理**
- 实时搜索
- 收藏 / 置顶 / 自定义分组，分组可拖动排序
- 长文本全文预览，可以直接原地编辑

![收藏、分组和托盘菜单](assets/screenshots/organize.png)

**⌨️ 键盘党友好**
- Windows 用 Win+V 呼出，macOS / Linux 用 Ctrl+Shift+V（可改），↑↓ 选择，Enter 直接粘贴回原窗口
- Ctrl+1 ~ 9 一键粘贴第 N 条
- Ctrl+P / S / D 置顶、收藏、删除，Tab 切换分组

**📡 局域网互传**
- Windows / Android 配对后，文本和图片一键发送，支持同 Wi-Fi 或 Tailscale IPv4
- ChaCha20-Poly1305 加密，点对点直连，**不经过任何服务器**
- 默认收到后存入历史，也可开启“收到后复制”；自动发送默认关闭

![发送端、接收端和配对设置](assets/screenshots/lan.png)

**🧩 其他**
- 浅色 / 深色 / 跟随系统三种外观，设置里一键切换
- 托盘常驻、开机自启、暂停记录
- 一键导出 / 导入备份，自动去重合并

![常规、存储、快捷键和数据设置](assets/screenshots/settings.png)

---

## 技术栈

| 模块 | 实现 |
|---|---|
| 应用框架 | Tauri 2 · Rust 2024 edition |
| 前端 | Vue 3.5 · TypeScript 6 · Vite 8 |
| 数据存储 | SQLite（rusqlite 内置编译，WAL 模式） |
| 剪贴板 | clipboard-master 监听 + arboard 读写 |
| 全局快捷键 | Windows 原生键盘钩子；macOS / Linux 全局快捷键插件 + Enigo 粘贴 |
| 图片处理 | image crate，支持 PNG / JPEG / GIF / WebP / BMP / TIFF |
| 内容去重 | blake3 |
| 传输加密 | ChaCha20-Poly1305 |

Release 构建启用 thin LTO、`opt-level=3`、`panic=abort` 和 strip。Windows 使用 WebView2，Linux 使用 WebKitGTK 4.1，macOS 使用系统 WKWebView。

CI 每次提交都会跑 `cargo fmt`、`cargo test` 和 `clippy -D warnings`。

---

## 上手

从 [GitHub Releases](https://github.com/BevalZ/clipkeep/releases/latest) 选择对应平台：

| 平台 | 安装包与使用条件 |
| --- | --- |
| Windows x64 | `windows-x64-setup.exe` 安装包，或 `windows-x64-portable.exe` 便携程序；依赖 WebView2 |
| Android 9+ | `android.apk` 为正式签名通用安装包；`.aab` 是应用商店用 bundle，不能直接安装 |

本次仅发布 Windows 和 Android 安装包。Windows 包未做 Authenticode 签名。安卓正式签名和校验方法见 [发布说明](RELEASE_NOTES.md)。

Windows 数据位于 `%APPDATA%\com.clipkeep.clipboard\`。迁移或卸载前可导出备份；卸载前先关闭自启。

### 局域网互传怎么配

1. **接收方**：设置 → 局域网传送 → 打开接收 → 复制配对码
2. **发送方**：同一页面 → 添加设备 → 粘贴配对码
3. 在任意文本条目右侧点 **发送** 图标，选择设备

Android 0.3.1 的“扫描响应设备”可发现同 Wi-Fi 客户端，输入对方配对码后可建立双向配对。手动配对码仍可单向添加；端口是 `45788/TCP+UDP`。0.3.1 新增 MagicDNS 名称、加密连接测试和后台服务恢复，Tailnet 的发现方式和 Android 后台剪贴板限制见 [Android 使用说明](android/README.md)。

### 从源码构建

先安装 Rust stable、Node.js 24 和目标平台的 Tauri 2 系统依赖。Linux 需 GTK 3、WebKitGTK 4.1、AppIndicator 和 X11 开发库。

```sh
npm --prefix ui ci
cd native
node ../ui/node_modules/@tauri-apps/cli/tauri.js dev
node ../ui/node_modules/@tauri-apps/cli/tauri.js build
```

从 `native` 运行 CLI，输出在 `native/target/release/bundle`。Windows 可加 `--no-bundle` 仅生成便携 exe。Android 构建见 [android/README.md](android/README.md)。

维护者可手动运行 GitHub Actions 的 **Release** 工作流，默认只生成构建产物；勾选 publish 才公开发布。流水线验证各端版本一致、测试并构建 Windows 和 Android，校验安卓签名，再创建版本 tag、上传安装包及 SHA-256 文件并公开 release。签名密钥通过仓库 Secrets 提供。

---

## 说在前面

- 日常使用没问题，欢迎大家帮忙找 bug
- Windows 默认以管理员身份运行，以支持往管理员权限的窗口里粘贴
- 数据只存在本机。本地数据库和备份文件暂时没有加密，敏感内容请留意
- 少数环境下 Win+V 会被系统抢走，可以在设置里改成 Ctrl+Shift+V

有问题或想要的功能，欢迎[提 Issue](https://github.com/BevalZ/clipkeep/issues)。觉得好用的话，点个 ⭐ 就是最大的支持 🙏

## 许可证

[MIT](LICENSE)
