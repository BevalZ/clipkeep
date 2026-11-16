# 与 Fomovet/clipkeep 的源码对比

对比日期：2026-11-16。当前 fork 在 Android、跨端传输、大历史存储和内存边界上增加了较多能力；原始仓库近期更集中于 Windows 粘贴焦点、截图兼容和安装更新。两边各有尚未同步的改动，版本号不能直接代表功能完整程度。

## 对比范围

| 项目 | 固定快照 |
| --- | --- |
| 当前 fork | [BevalZ/clipkeep `98c5624`](https://github.com/BevalZ/clipkeep/commit/98c56242942e928a1e22f2cce2f44928186b49eb)，应用版本 0.4.2 |
| 原始仓库 | [Fomovet/clipkeep `9d369e7`](https://github.com/Fomovet/clipkeep/commit/9d369e79907c95939752db67a445743be192704f)，2026-11-10，应用版本 0.2.4 |
| 共同祖先 | `8c4f66dbbc51d9a2de3e5064c832d9084b41d51a` |
| 分叉情况 | fork 独有 11 个提交，上游独有 15 个提交，包含合并及版本提交 |
| 两个目录树的差异 | 92 个文件，以上游到 fork 方向统计为 +8434 / -5609 行 |

统计以以上代码快照为准，不包含本报告的文档提交。差异中的缺失文件可能是上游在分叉后新增、fork 尚未引入的文件，并不代表 fork 曾经删除这些功能。

本次已添加 `upstream` 远端并获取源码，没有合并上游。结论基于 Git 历史、源码、现有测试和基准记录；没有在相同 Windows 设备上运行两个版本做性能 A/B。[PERFORMANCE.md](PERFORMANCE.md) 中的“优化前”是本 fork 本轮性能修改前的实现，不能据此声称相对原始仓库快多少倍。

## 功能与实现差异

| 维度 | 当前 fork | 原始仓库 | 判断 |
| --- | --- | --- | --- |
| 平台 | Windows 桌面及原生 Java Android；另有 Linux/macOS 适配代码，目前发布 Windows/Android | 以 Windows 为支持目标 | fork 的主要产品扩展在 Android，其他桌面平台仍需独立验证 |
| Android | 文本/图片、搜索、收藏/置顶、后台接收、预览缓存、扫描配对与连接检查 | 没有 Android 客户端 | 保留 fork 实现 |
| 加密互传 | 文本和图片、自动发送、可选接收后复制、Wi-Fi/Tailnet/MagicDNS、分块和取消 | 以 CKL1 文本传输为主 | 保留 fork 协议；旧文本兼容不代表旧客户端支持图片和分块 |
| 去重与容量 | schema 4：32 字节内容摘要索引筛选，再核对完整内容；触发器增量维护容量统计 | schema 3：基于 hash 去重，容量统计仍做聚合扫描 | 保留 fork 的精确匹配、事务和统计优化 |
| 列表 | `shallowRef`、固定行高虚拟列表、隐藏释放、刷新合并 | 每次增加约 100 行的渐进渲染，已显示的行继续保留 | 长列表继续使用 fork 方案 |
| 备份内存 | 逐条解析、磁盘暂存，导出 ID 按 128 条分批 | 已做到一次只解码一张图片，但仍整份反序列化备份并保留记录集合，导出收集全部 ID | 不回退 fork 的有界处理 |
| 数据可靠性 | 损坏库连同 WAL/图片归档、恢复日志续做、图片入库期间保护、配额拒绝时回滚 | 缺少 fork 本轮新增的这些保护 | 保留恢复及故障回归测试 |
| Windows 粘贴 | 记录前台窗口，固定等待 80 ms 后发 Ctrl+V；命令错误主要写日志 | 跟踪原窗口与子控件，校验会话、焦点及修饰键；处理 Explorer 重命名和固定面板 | 优先移植上游修复 |
| 截图读取 | 主要使用 arboard，已有图片尺寸/内存边界检查 | 增加 DIB/DIBV5 fallback，处理部分截图工具的格式兼容问题 | 优先移植 decoder，保留 fork 的资源限制 |
| 安装与更新 | 手动 Release 工作流，Windows/Android 产物与安卓签名校验 | 应用内更新、更新提示、自启安装卸载 hooks、旧便携实例处理 | 借鉴流程，先适配 fork 身份和资产命名 |
| 易用性 | 尚无以下两个上游功能 | HEX 色值预览、图片粘贴文件命名 | 可独立补齐 |

底层仍采用相近的 Tauri 2、Rust、Vue 3、SQLite WAL、arboard 和 ChaCha20-Poly1305。差异主要在业务路径及平台适配，不需要整体重写技术栈。

## 优先改进项

### P1：Windows 焦点恢复与粘贴结果反馈

当前 [`paste_to_previous_window`](native/src/win.rs) 保存的只是前台 HWND，调用 `SetForegroundWindow` 后固定等待 80 ms，再发送 Ctrl+V。它没有确认目标子控件已恢复，也没有等待用户释放修饰键。当前 [`paste_clip` / `copy_clip`](native/src/lib.rs) 先隐藏窗口，后台任务错误只输出日志，用户可能无法知道粘贴为何没有完成。

上游的 [focus.rs](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/focus.rs) 保存窗口、子控件及进程/线程身份，用 generation 和 busy 状态限制过期及并发操作；最多等待 500 ms、每 10 ms 检查一次焦点与按键条件。目标失效或超时时停止自动粘贴并返回错误。Explorer 重命名模式会避免激活面板导致文件名编辑框消失，固定面板也能更新同一窗口内不同输入控件的目标。

建议把焦点模块、Windows 窗口控制、IPC 返回结果和前端忙碌/错误提示作为一个完整改动移植，保留 fork 的异步读取、虚拟列表与非 Windows 适配。相关提交依赖顺序为 `59e6096` → `2757893` → `6ce1bf3` → `5ccbe03` → `0dc3801`；不能只摘最后一个修复。提交内还包含其他功能，应按最终实现拆分移植。

验收：普通/管理员窗口、同窗口两个输入框、Explorer F2 重命名、固定面板、连续快速粘贴、长按修饰键、目标关闭/卡死、图片解码期间重新呼出窗口。确认不会向错误目标发送按键，失败时有可见反馈。

### P1：截图 DIB fallback

上游 [dib.rs](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/dib.rs) 专门处理 arboard 无法转换的部分剪贴板 DIB。源码说明了 PixPin 等产生的 32 位 BI_RGB DIBV5 在转换中像素偏移不匹配的问题，并覆盖 24/32 位、BI_RGB/BI_BITFIELDS、方向、alpha、位掩码及截断边界。

建议独立引入 decoder 和测试，再接入 [上游图片读取回退路径](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/clipboard.rs#L533)。当前的头部尺寸检查不能替代兼容解码；移植后继续保留 fork 的像素限制、`IMAGE_WORK`、图片入库保护和配额回滚。

验收：decoder 边界测试加入便携核心测试；Windows 上实际复制系统截图、PixPin、透明图片、资源管理器图片文件，并检查像素、alpha、保存及再次粘贴。畸形或超大输入必须被拒绝且不破坏已有历史。

### P1：明确 fork 的升级来源与数据兼容边界

两边当前的 `productName` 和 `identifier` 相同，Windows 数据目录都是 `%APPDATA%\com.clipkeep.clipboard\`，单实例识别也有重叠。fork 已迁移到 schema 4，schema 3 的旧程序会拒绝打开新库；不能把切换原始版 exe 当成可直接使用的降级方式。

上游 [update.rs](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/update.rs#L22) 将仓库写死为 `Fomovet/clipkeep`，并筛选 `_x64-setup.exe` 结尾的资产；fork 的 [发布脚本](scripts/release.py) 生成 `clipkeep-{version}-windows-x64-setup.exe`。原样移植会使用错误的升级来源，且资产匹配规则也不适用。

建议先明确“沿用身份、就地升级”还是“独立身份、支持并存”的产品策略。沿用身份时必须固定 fork 更新源、校验平台和资产命名、拦截不兼容降级；独立身份时需要明确的数据迁移入口，不能只改 identifier。上游的 GitHub asset SHA-256 校验可用于下载完整性检查，但不等同于独立发布者签名；fork Windows 当前也未做 Authenticode 签名。

同时借鉴 [NSIS hooks](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/installer-hooks.nsh)，让升级保留用户自启选择、真实卸载清理自启项。上游处理旧便携实例的 `TerminateProcess` 逻辑不宜直接照搬，应优先正常退出、等待事务完成，严格识别同产品实例。

验收：首次安装、覆盖升级、安装版与便携版切换、自启开关保留、卸载残留、网络失败、下载中断、摘要不匹配、原仓库版本误入更新源、schema 4 降级拒绝和数据保留。

### P2：降低隐藏窗口内存和首屏等待

上游 [set_low_memory](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/lib.rs#L1611) 在隐藏/显示时切换 WebView2 的 `SetMemoryUsageTargetLevel(LOW/NORMAL)`；接口需要 Runtime 114+，旧版运行时保留默认行为。这是内存目标级别调整，不是挂起 WebView，也不保证释放固定数量的内存。可与 fork 的隐藏释放、虚拟列表叠加，收益需要测量。

另一个可借鉴点是上游 [onShown](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/ui/src/App.vue#L801) 并行加载分组、历史和暂停状态；fork 的 [onShown](ui/src/App.vue) 目前依次等待。可合并首屏 RPC 或并行独立查询，但必须保留分组删除后的回退和隐藏期间对过期响应的丢弃，避免引入错列表或后台刷新。

验收：同机 Windows release 构建，记录首次/再次呼出 p50/p95、隐藏后稳定工作集及 WebView 子进程内存；用相同文本/图片历史测量，同时回归隐藏中更新、恢复分组、快速反复呼出和旧 Runtime。

### P2：图片文件命名与 HEX 色值预览

上游 [write_image](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/clipboard.rs#L598) 通过临时 PNG 和 CF_HDROP 提供图片文件名，[paste_file_name](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/lib.rs#L489) 过滤非法及保留名称，可改善目标网站反复得到 `image.png` 的体验。移植时需要有界保留或过期清理，避免每次粘贴立即清空目录使尚未读取文件的目标程序失效。

上游 [hex_color](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/src/db.rs#L106) 支持 `#RGB`、`#RGBA`、`#RRGGBB`、`#RRGGBBAA`。可独立加入预览数据和色块，不改变正文、搜索和复制内容；色块需保持虚拟列表行高不变。

## 不能只靠同步上游解决的问题

### 协议防重放与跨端一致性

[当前协议](LAN_PROTOCOL.md) 的分块 ACK 已绑定 transfer ID，但旧单帧 ACK 仍是加密的 `OK`，接收端也没有完整的跨连接/重启消息去重机制。随机 nonce 和认证加密不自动提供防重放；内容去重也不能代替协议层处理，因为重复接收可能仍有更新时间、复制或通知等副作用。

建议为后续协议定义请求 ID、与请求绑定的 ACK、接收端重放记录及兼容策略，再让 Rust/Java 共享一组黄金测试向量。需要明确旧客户端允许哪些降级行为，不能仅修改一端或默认回退时仍保留相同安全属性。

验收至少包括重放消息/ACK、乱序或缺块、未知版本、连接中断、接收成功但 ACK 丢失、取消与保存竞争，并覆盖 fork 新旧端与原始文本客户端的兼容矩阵。

### 搜索与批量写入还有成本

当前摘要索引和增量统计主要优化日常保存、容量检查、收藏/分组列表及去重。它们不加速全部查询：现有基准中完整子串搜索在 10 万条历史下约慢 10%，批量造数写入更慢，数据库约增加 2.3%。这是本轮 fork 前后结果，不是与上游的性能比较。

后续优先对真实数据分布测量搜索和导入，再评估可选 FTS5 trigram 候选检索加原 LIKE 复核。短词、Unicode、大小写以及 `%` / `_` 转义必须保留原语义；不能直接把分词搜索替换子串搜索。减少索引维护成本也需同时观察日常保存收益，不能只追求批量写入分数。

### 自动化验证尚未覆盖真实桌面和全部 Android 生命周期

当前 [CI](.github/workflows/ci.yml) 覆盖 Windows Rust 测试/Clippy、前端构建、便携存储测试，以及 Android APK 构建、JVM 测试和 Lint。Android 测试 APK 虽然会构建，但未运行 connected tests；[浏览器回归脚本](ui/checks) 也未接入 CI。

建议接入浏览器模拟 IPC 回归、支持 KVM 的 Android 设备测试，并建立可交互 Windows 验证流程。上游的 [Windows 焦点测试说明](https://github.com/Fomovet/clipkeep/blob/9d369e79907c95939752db67a445743be192704f/native/tests/README.md) 和相应脚本可以作为基础；编译通过不能证明真实剪贴板及焦点切换正确。

本轮已通过桌面 Rust 57 项、便携核心 38 项（有重叠）、两套 Clippy、前端构建与两组浏览器回归；Android 构建、10 项 JVM 测试、Lint 和 26 项设备检查通过。另有 `PreviewLifecycleTest` 的 `onStop` 三秒等待断言未通过，同环境换回优化前存储实现也失败，仍需真机或硬件加速模拟器复核；不计入通过项。Windows 原生剪贴板、WebView2 和完整应用性能尚未实测。详细过程见 [PERFORMANCE.md](PERFORMANCE.md) 和 [RELIABILITY.md](RELIABILITY.md)。

## 建议实施顺序

1. 移植 Windows 焦点会话、错误反馈和配套测试，解决粘贴目标正确性。
2. 独立移植 DIB decoder 与截图回退，扩展图片来源兼容性。
3. 明确 fork 身份和迁移边界，再实现独立更新源、安装卸载 hooks。
4. 以 Windows 实测验证 WebView 内存目标和首屏加载优化；同时补齐自动化回归。
5. 按实际需求补充图片命名、色值预览；另外安排协议防重放和全文搜索优化。

每项宜单独提交并保留现有 Android、LAN、schema 4、恢复归档、虚拟列表和备份流式处理的回归检查。按功能移植比整仓覆盖更便于判断行为是否退化。
