# 内存优化与验证

这次改动保留 Windows 文字、图片和备份格式功能，并新增原生 Android 文本客户端。下面的数字描述具体测试场景，不代表所有设备或整个 Windows 应用的常驻内存。

## 已实现

- Windows 导入：用 serde 逐条读取 `.ckbak`，每次只解码一张图片，将结果暂存到磁盘；整个备份验证通过后才用事务合并。图片从暂存区流式复制，不再次解码。正常完成或错误返回时自动清理暂存目录。
- Windows 导出：固定最大 ID，分批读取最多 128 个 ID，避免一次收集全部历史 ID。大文本和图片仍按条导出。
- SQLite：限制连接页缓存目标为 2 MiB，语句缓存 8 条，临时排序使用文件，关闭 mmap；旧数据库迁移逐行进行，列表不复制预览字符串。
- Vue：`shallowRef` 保存最多 1000 条已有列表预览；固定行高虚拟列表只挂载可见区域及少量预加载行。缩略图随条目离开 DOM，隐藏时释放列表和全文；隐藏期间不请求列表，正在进行的旧请求不能把内容填回隐藏窗口。刷新请求合并为一个正在执行的请求和一个待处理请求。
- 窗口：设置界面、托盘界面按需加载代码；托盘 WebView 首次打开才创建，关闭后销毁，避免长期维护第二个隐藏页面。
- 图片：解码 PNG 前检查像素数；导入、图片文件捕获和图片粘贴的应用侧大缓冲区串行处理。保留原有 4000 万像素上限，不通过缩小原图丢失内容。
- 局域网：Rust 在同一帧缓冲区内加密/解密，减少整份密文/明文复制；协议和确认机制保持兼容。
- Android：使用系统 ListView 回收视图；每页 80 条、400 字预览，不把全文装入列表或 CursorWindow。数据库/发送任务单工作线程、有限队列，闲置线程退出；接收使用一个独立线程、一次处理一个连接；应用离开前台释放列表并关闭 TCP 接收。

## 可复现的导入峰值测试

Linux x86_64、Rust 1.99.1，release 构建，同一个包含 **64 张不同颜色的 1024×1024 RGBA PNG** 的备份，每张解码后 4 MiB。`legacy` 模式复现改动前“整份读取、反序列化、所有图片解码后保留”的流程；`stream` 模式调用实际的新导入暂存实现。

| 导入流程 | 子进程峰值 RSS | 用时 |
| --- | ---: | ---: |
| 原流程 | 267396 KiB（261.1 MiB） | 0.18 s |
| 逐条暂存 | 7360 KiB（7.2 MiB） | 0.41 s |

本场景峰值降低约 **97.2%**，代价是额外磁盘读写。数据不包含 WebView2、系统剪贴板或最终合并事务的整个应用内存。复杂图片、单条巨大文本、解码器和系统缓冲区仍会提高峰值；SQLite 的缓存值也不是整个进程的硬内存上限。

```sh
cargo build --manifest-path native/core-tests/Cargo.toml --release --bin import_memory
native/core-tests/target/release/import_memory generate /tmp/clipkeep-memory.ckbak 64
/usr/bin/time -v native/core-tests/target/release/import_memory legacy /tmp/clipkeep-memory.ckbak
/usr/bin/time -v native/core-tests/target/release/import_memory stream /tmp/clipkeep-memory.ckbak
```

没有 GNU time 时，可在 Linux Python 中用 `os.fork`、`os.execl` 启动每个模式，再以 `os.wait4` 返回的 `rusage.ru_maxrss` 读取独立峰值。

暂存会占用额外磁盘空间；进程被强制终止时，数据目录中的 `import-*` 暂存目录可能遗留。数据库合并仍是事务；若合并阶段因磁盘错误中止，已复制但未被数据库引用的图片由既有启动垃圾回收清理。

## 其他验证

- 18 项可移植 Rust 测试覆盖真实数据库、图片存储、逐条导入、导出 ID 分批和独立加密向量。
- Windows 目标 `cargo clippy --target x86_64-pc-windows-gnu --all-targets -- -D warnings` 通过。当前机器为 Linux，尚未实测 Windows WebView2 常驻内存和托盘窗口生命周期；Windows CI 保留完整 `cargo test`。
- Chromium + 模拟 Tauri IPC：1000 条记录，400×600 窗口首屏只挂载 12 条；30 次连续变更合并成 2 次查询；滚动到第 1000 条、隐藏后释放、隐藏时 30 次变更不触发查询、重新显示和设置页返回后恢复滚动已验证。这不是 Windows 原生 IPC/窗口行为测试。
- Android：4 项 JVM 协议测试、3 项 Android 15 设备测试、APK 构建和 lint；测试依赖只进入测试 APK，主 APK 没有第三方运行时依赖。

```sh
cargo test --manifest-path native/core-tests/Cargo.toml --locked
cargo clippy --manifest-path native/core-tests/Cargo.toml --locked --all-targets -- -D warnings
npm --prefix ui ci
npm --prefix ui run build
# 启动 Vite 并用 playwright-cli 打开其页面后，运行持久化的浏览器回归检查：
# playwright-cli run-code --filename ui/checks/virtual-list.js
```

要测 Windows 整体效果，应在同一机器、同一数据库上比较旧版本和新版本：冷启动、隐藏空闲、显示 1000 条图片历史、连续粘贴大图、导入相同备份。使用 Process Explorer 同时记录主进程与全部 WebView2 子进程的 Private Bytes / Working Set，避免只测 Rust 主进程。
