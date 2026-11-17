# 上游 Windows 改进移植记录

日期：2026-11-17。基于 Fomovet/clipkeep 的 [`9d369e7`](https://github.com/Fomovet/clipkeep/commit/9d369e79907c95939752db67a445743be192704f) 移植 Windows 改进，并吸收最新 [`8c8e370`](https://github.com/Fomovet/clipkeep/commit/8c8e3702b2adcc888da2e220921872bc97c75eb8) 中的更新源优先级和 WebView2 恢复修复。上游代码沿用仓库的 MIT 许可证。本次按功能适配，没有合并整个上游分支。

## 已完成

| 改进 | 实现与适配 |
| --- | --- |
| 粘贴焦点 | 引入窗口/子控件身份、会话代次和并发保护；恢复焦点并确认修饰键释放后才发送粘贴，失效或超时返回错误。固定面板粘贴保持打开及原排序，支持同窗口切换输入框。 |
| Explorer 重命名 | 使用不激活面板的显示路径保留文件名编辑框；搜索在该模式禁用，界面说明键盘仍由资源管理器接收。 |
| 错误反馈 | IPC 返回准备/恢复失败；前端限制重复操作，过期操作不会解锁新会话，隐藏后的失败在下次打开时显示。 |
| 截图兼容 | DIB/DIBV5 解码回退、较长图片读取重试、空白文本附带图片及 CF_HDROP 回退。保留像素上限，限制 DIB 拷贝大小，并在重试时重新检查排除标记和尺寸。 |
| 捕获处理 | 监听与保存分离，按顺序保存；队列只允许一条等待记录，替代上游 16 条原始图片队列，避免积压大量 RGBA。文件图片也走同一工作线程，保留自动发送及图片处理锁。 |
| 热键与窗口 | 热键通过 `unpark` 唤醒，取消 5 ms 空闲轮询；禁用 Windows 弹窗过渡动画。 |
| 隐藏内存 | 隐藏时设置 WebView2 LOW 内存目标，显示前恢复 NORMAL；旧 Runtime 不支持时保留默认行为。原有虚拟列表和隐藏释放继续使用。 |
| 首屏与托盘 | 分组、历史和暂停状态并行读取；隐藏及过期响应被丢弃，删除分组后回退。托盘窗口异步创建，页面渲染完才显示，关闭后销毁。 |
| WebView2 故障 | 渲染进程退出时隐藏并重载；浏览器进程退出时隐藏透明窗口并重启，恢复标记和冷却时间阻止重启循环，正常退出不触发恢复。 |
| 应用内更新 | 设置、托盘及红点提示；支持检查、下载、取消、确认安装。仅使用 `BevalZ/clipkeep`，GitHub 为主，jsDelivr 仅在 GitHub 不可达时提供版本提示。 |
| 更新校验 | 严格稳定版本比较，匹配 `clipkeep-{version}-windows-x64-setup.exe` 及本仓库地址；限制元数据/安装包大小，核对长度和 SHA-256。安装前再次锁定并校验文件，检查/下载/安装互斥。Windows x64 安装版可原位更新，便携版及其他平台前往发布页。 |
| 安装自启 | NSIS 简体中文、用户级安装、首次安装自启标记和卸载清理；自启任务明确当前用户、电池策略、无限运行时长与优先级，更新已有任务时保留用户开关。 |
| 图片命名 | 可开启“图片粘贴时附带文件名”，默认关闭；同时提供 PNG 文件及位图。每次使用独立目录，近期文件不因再次粘贴被删除。缓存上限 256 MiB / 256 项，保留 24 小时；满额时退回位图粘贴，过期内容在下次文件粘贴时清理。读取原 PNG 限制为 16 MiB，超限仍可粘贴位图。 |
| HEX 预览 | 完整短文本为 `#RGB`、`#RGBA`、`#RRGGBB`、`#RRGGBBAA` 时显示色块；截断预览不推断全文颜色，正文和虚拟列表行高不变。 |

## 保留与边界

- 应用继续沿用 `com.clipkeep.clipboard` 和原数据目录，更新源固定为本 fork。schema 4、数据库恢复归档、精确去重、容量统计、异步查询、流式备份、设置补丁、Android 和 LAN 协议继续保留。
- 自启任务仅升级当前路径的定义或修复已不存在的路径；另一个有效安装路径保留，用户可在当前版本中重新开启自启来切换。没有移植上游强制终止旧进程的逻辑；从其他仍在运行的便携版切换时，先从托盘正常退出旧实例。
- SHA-256 是下载完整性验证，不是 Authenticode 发布者签名。版本号仍为 0.4.2，本轮源码提交没有创建 tag 或发布安装包。
- 本轮对应此前报告中的 Windows 移植项。上游此次新增的 macOS 原生模块/DMG 发布不在 Windows/Android 发行范围内；现有非 Windows 适配继续保留。
- 防重放协议升级和全文搜索索引属于独立改进，未混入此次上游移植。

## 验证

- Linux 桌面 Rust：79 项测试通过；便携核心：51 项通过，两套包含重叠测试。
- Linux 桌面与便携核心全目标 Clippy 无警告；Windows GNU 全目标交叉 Clippy/编译检查通过，包含 Windows 专属模块和焦点测试程序。两套 Rust 格式检查通过。
- 前端 TypeScript/Vite 构建通过。浏览器输入法/有序设置保存、虚拟列表、焦点 IPC、更新与托盘共四组检查通过。1000 条列表仍只挂载 12 行，30 次刷新事件产生 2 次列表请求。
- 新增 CI 浏览器任务执行焦点 IPC 和更新/托盘检查；Windows 焦点实机测试脚本及说明位于 [native/tests](native/tests/README.md)。
- 没有在本机运行 Windows GUI、真实截图工具或安装器；交叉编译和模拟 IPC 不能替代这些验收，也未测量 Windows 实际内存/呼出耗时的变化。
- 本轮没有修改 Android；此前 Android 生命周期时序断言待实机复核的状态见 [PERFORMANCE.md](PERFORMANCE.md)。

复现命令：

```sh
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path native/core-tests/Cargo.toml --locked
cargo clippy --manifest-path native/core-tests/Cargo.toml --locked --all-targets -- -D warnings
npm --prefix ui run build
```

浏览器检查先启动 `npm --prefix ui run dev`，安装 Python Playwright 和 Chromium，然后执行：

```sh
python ui/tests/focus_ipc_smoke.py
python ui/tests/upstream_smoke.py
# 在已打开 Vite 页面的专用 playwright-cli 会话运行：
playwright-cli run-code --filename ui/checks/reliability.js
playwright-cli run-code --filename ui/checks/virtual-list.js
```

Windows 实机验收仍需覆盖 Explorer F2、管理员窗口、同窗口不同输入框、连续图片粘贴、WebView2 进程退出、首次安装/覆盖升级/卸载和断网更新。测试安装应使用独立目录与标识，避免影响日常剪贴板历史。
