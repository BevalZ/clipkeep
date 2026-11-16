# 可靠性修复与验证

本轮修复数据库恢复、图片文件生命周期、Android 图片分享地址、输入法按键、容量不足时的保存结果及设置并发更新。局域网协议版本和旧版兼容行为尚未改变，防重放升级需另行处理。

## 数据恢复与图片

- 桌面端只有确认数据库损坏时才创建新历史。旧数据库、WAL/SHM、原图和缩略图一起移动到数据目录下的 `recovery-*` 目录；新库的垃圾回收不会访问该目录。
- `recovery-pending.json` 记录未完成的归档。启动时先恢复归档，遇到错误停止启动并保留进度，不创建可能触发误清理的新库。不要删除恢复目录或进度文件来绕过错误；应先保留副本并检查错误原因。
- 图片写入返回的 `Saved` 在入库完成前保护对应文件。并发删除会跳过正在保存的图片；写入失败可能留下无引用文件，由下次启动时的垃圾回收处理。
- 保存后若容量清理无法保留本次内容，整个写入和清理事务回滚，接收端不会返回成功确认。收藏等受保护记录仍不计入普通历史容量。

## Android 分享

图片 URI 同时包含记录 ID 和图片哈希文件名，解析时核对两者。删除最大 ID 后即使 SQLite 复用该 ID，旧 URI 也不能读取不同的新图片。升级前复制的 ID-only 图片 URI 将失效，需要从历史重新复制或分享；原图本身不受影响。

## 桌面交互

- 输入法组合输入期间不处理应用快捷键，兼容 `isComposing` 和 WebView 的 `keyCode=229`。
- 设置命令只发送修改字段；后端在锁内合并当前值。前端按顺序提交、忽略过期响应，设置页重新打开时等待排队写入完成。

## 回归检查

本次 Linux 环境验证：桌面 Rust 测试 54 项、便携核心测试 35 项（两者有重叠）通过，两个目标的 Clippy 及前端构建通过；浏览器可靠性和虚拟列表检查通过。Android 主程序与测试 APK 构建、10 项单元测试及 Lint 通过。

后续性能优化中已完成 Android 15 独立模拟器的 20 项存储回归，包含新增 URI 复用检查：软件模拟启动超时的问题通过延长系统启动超时、关闭模拟器蓝牙并预编译测试代码绕过。最新验证结果见 [性能优化与基准](PERFORMANCE.md)。Windows 原生剪贴板和 WebView2 尚未实测。

```sh
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path native/core-tests/Cargo.toml --locked
npm --prefix ui run build
```

启动 Vite，在专用 Playwright CLI 会话打开页面，然后执行：

```sh
playwright-cli run-code --filename ui/checks/reliability.js
playwright-cli run-code --filename ui/checks/virtual-list.js
```

浏览器检查使用模拟 IPC，覆盖输入法确认/导航、正常快捷键、快速修改设置、失败后继续保存以及保存途中退出再返回；不代替 Windows WebView2 和原生剪贴板实测。

Android 使用专用模拟器，设备测试会重置测试安装的历史：

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

新增 `testImageUriCannotReadReplacementAfterRowIdReuse` 检查旧 URI、删除后的 URI、复用 ID 的新图片和旧版 ID-only URI，调用图片 Provider 实际使用的解析路径。
