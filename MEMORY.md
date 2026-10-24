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

## 0.3.0 网络与图片扩展

Android 图片保存在私有文件目录，列表只读元数据，预览按需采样；导入先检查尺寸，网络编码上限 8 MiB、1600 万像素。桌面图片发送与接收使用不同的工作锁，避免两端同时发图时互等。桌面自动发送队列只保存一条待发记录的哈希，突发复制优先保留最新内容。

新增后台接收会保留 Android 前台服务、TCP/UDP 接收线程和必要的数据库资源，不能再套用“退后台停止网络”的旧描述。服务由用户开启，可从应用或通知停止；没有持续剪贴板轮询或无限 wakelock。收到图片的解密缓冲区和解码像素仍会占用内存，上限不等于整个进程的硬内存限制。

## 0.4.2 重复项自动清理

Windows 数据库和 Android 历史都在启动、保存、导入 / 接收及编辑路径按 `kind + content` 检查重复项。以 `updated_at` 最新、ID 最大者为存活记录；其余记录删除，收藏、置顶、分组、创建时间、更新时间和使用次数合并到存活记录。Windows 同时用正确哈希冲突处理编辑到已有文本的情况，自动删除正在编辑的重复行。

唯一哈希约束已经阻止大多数新重复项，但不能处理旧版本或导入时留下的不同哈希；这次修正专门覆盖这类数据。Windows 删除的图片文件仍经过引用检查，Android 启动和保存后扫描私有图片目录，只有零引用文件才会删除。没有增加按相似度或大小的模糊匹配，只有同类型、同内容才算重复。

## 0.4.1 安卓图片预览

预览仍然按需解码，不在历史列表加载位图；最长边限制为 1024 像素，修正非整除尺寸取样后超出此边界的问题。原始 PNG 和传输字节不受影响。前台增加最多 4 MiB 的 LRU 缓存，按 `Bitmap.getAllocationByteCount()` 计数，避免反复打开同一张图片时重新解码。这个取舍会保留少量前台内存，以减少重复解码和分配；不是应用总内存下降的量化结论。

退后台、内存压力、删除和清理普通历史时清空缓存，并使旧加载请求失效。解码不持有清理锁；解码期间缓存失效时，未展示的新位图直接释放，避免后台重新填满缓存。已经展示过的位图在淘汰时仅解除缓存引用，防止正在绘制时被回收；预览对话框关闭时解除 ImageView 引用。缓存预算不包含正在显示但已淘汰的位图、临时解码缓冲或图片导入 / 校验的完整像素内存。

新增设备测试验证 2049×1537 图片取样后的长边限制与原文件完整性、缓存复用及按字节淘汰、超出额度的位图不缓存、失效请求不会命中缓存和损坏文件可重试；另验证预览排队期间退后台再返回，旧预览不出现且新请求仍可用。尚未测量 Android 整体进程 RSS / PSS 的前后差值。
