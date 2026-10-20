## clipkeep v0.2.0

- 内存优化：备份逐条暂存导入、SQLite 缓存限制、虚拟列表、隐藏后释放数据、托盘页面按需创建、局域网帧原地加解密。
- 64 张 1024×1024 RGBA 图片的导入基准中，峰值 RSS 从 261.1 MiB 降至 7.2 MiB（约 97.2%）；这是导入模块基准，不是整个桌面进程的内存承诺。方法见 MEMORY.md。
- 本次仅发布 Windows x64 和 Android，保留 Windows 文字、图片、分组、备份及局域网文本传送。
- 新增 Android 9+ 原生文本客户端：分页历史、搜索、收藏、置顶、系统分享导入、主动粘贴保存及 CKL1 加密文本互传。

## 下载

| 文件后缀 | 用途 |
| --- | --- |
| windows-x64-setup.exe | Windows x64 安装程序 |
| windows-x64-portable.exe | Windows x64 便携程序 |
| android.apk | Android 9+ 正式签名通用安装包 |
| android.aab | 已签名的商店分发 bundle，不能直接安装 |

所有安装包由同一提交在 GitHub Actions 构建，SHA256SUMS 用于核对下载文件。安卓签名已通过 apksigner 验证，证书 SHA-256：

```
1acac42eccdc8cd63b98d86a4fb5831e6637fbeabd71c81d8fecf6442c1385ee
```

## 平台说明

- Windows 默认 Win+V，支持管理员窗口粘贴；此版本没有 Authenticode 签名。
- Android 首版仅支持文本，无后台剪贴板监听；局域网接收只在前台开启，切到后台即关闭，返回后需主动开启。图片、分组及桌面备份导入尚未实现。调试包与正式包签名不同，不能直接覆盖安装。
- 自动构建和单元测试不等同于所有桌面环境的 GUI 实机验证。Android 已完成 API 35 模拟器上的设备测试和双向文本互传验证；API 28 真机尚未验证。
