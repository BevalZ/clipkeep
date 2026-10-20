# clipkeep Android

Android 9（API 28）及以上的原生文本客户端。Java + 系统控件 + SQLite，无 WebView、无生产环境第三方依赖。通用 APK 同时适用于 ARM 和 x86 设备。

支持主动粘贴保存、新建文本、系统分享/选中文本导入、全文搜索、分页历史、复制、预览、收藏、置顶、删除和局域网加密互传。重复文本合并；普通历史默认最多 50 MB，收藏和置顶不参与自动清理。列表每页 80 条，只读取 400 字预览；完整内容仅在操作时读取。预览最多展示 16000 字，复制和局域网发送使用全文，文本上限为 UTF-8 2 MiB。

这是文本客户端首版，图片、分组和桌面 `.ckbak` 备份导入/导出尚未实现。与桌面版互通的是文本发送协议，不是数据库同步。

## 安装和构建

需要 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0。设置 `ANDROID_HOME`，或在本目录创建不提交的 `local.properties`，填写 `sdk.dir=/你的/Android/SDK`。

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。首次构建会下载 Gradle 和构建依赖。Gradle 分发包的 SHA-256 已固定在 wrapper 配置中。

产物 `app/build/outputs/apk/debug/app-debug.apk` 是可直接安装的测试签名包。正式发布由 GitHub Actions 的 Release 工作流完成，生成签名 APK 和 AAB。维护者需配置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个仓库 Secrets。本地构建则设置 `ANDROID_KEYSTORE_PATH` 指向密钥文件，并提供后三个环境变量，再运行 `./gradlew :app:assembleRelease :app:bundleRelease`。缺少密钥时 release 构建无法完成签名；不要将密钥或口令提交进仓库。

官方发布证书 SHA-256：`1acac42eccdc8cd63b98d86a4fb5831e6637fbeabd71c81d8fecf6442c1385ee`。后续更新必须沿用这把密钥。使用 SDK 中的 `apksigner verify --verbose --print-certs <APK>` 验证；调试签名版与正式签名版不同，不能直接覆盖安装。

## 和桌面端互传

1. 两端连接同一局域网。接收端开启“接收”，复制该端的配对码。
2. 发送端添加设备并粘贴配对码。
3. Android 长按文本 → 发送到设备；桌面端使用条目的发送菜单。
4. 双向互传需要两边各添加一次。协议为现有 `cklan1://` / `CKL1`，端口 `45788/TCP`，ChaCha20-Poly1305，包含加密接收确认。只有成功保存后才发回确认。

Android 接收只在应用前台开启，切换应用或锁屏会关闭；重新进入后需要主动开启。收到的文本只进入历史，不覆盖系统剪贴板。密钥保存在应用私有设置中，配对码不会进入历史；重置配对码会使旧码失效。系统云备份和设备迁移都不包含历史与密钥。

Android 10+ 不允许普通后台应用随时读取其他应用的剪贴板。本客户端通过“粘贴保存”、系统分享和选中文本菜单收集内容，没有后台监听、轮询、无障碍服务或常驻前台服务。系统分享对超长文本存在 Binder 限制，超过 64000 个 UTF-16 单元时请用局域网发送。

## 验证

```sh
# JVM：独立加密向量、中文/emoji、2 MiB 上限、篡改和非法配对码
./gradlew :app:testDebugUnitTest

# 连接测试设备或模拟器后：SQLite 分页、去重、收藏保护、Unicode 分块读取、设备加密提供者
# 使用专用测试安装环境；测试会重置测试安装的历史数据库。
./gradlew :app:connectedDebugAndroidTest

adb shell dumpsys meminfo com.clipkeep.android
```

已在 Android 15 / API 35 x86_64 模拟器上安装和运行，并验证双向 CKL1 文本收发及切到后台关闭接收。最低 API 28 已通过编译检查，但尚未在 Android 9 真机验证。桌面核心及内存测量方法见 [MEMORY.md](../MEMORY.md)。
