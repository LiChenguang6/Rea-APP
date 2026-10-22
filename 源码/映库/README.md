# 映库 CineShelf

独立安装的 Android 本地视频影库与播放器。包名 `org.rea.cineshelf`，Android 8.0+，不申请联网权限。不会修改旧 X2 / 51 App，也不读其私有队列或绕过权限。

## 使用

1. 安装 `CineShelf-preview.apk`。从首页或设置添加一个或多个**实际保存视频的目录**，允许系统文件选择器提供的目录权限。
若系统选择器只显示“最近 / 下载”，可从右上菜单开启“显示内部存储”，再从左侧选择设备目录。

2. 扫描会读取视频并整理封面。完全相同的副本只显示一次；菜单可查看所有真实路径。目录授权失败时重新添加目录。
3. 点击封面播放，支持进度保存、±10 秒、变速、横屏。最近页可继续观看。使用系统解码器；不保证手机不支持的编码、加密缓存或 DRM 文件可播放。
4. 清理页中，先选择保留路径，再确认清理。系统会重新完整读取所有待删副本和保留文件，比对 SHA-256 后删除。请暂停其它 App 的下载、移动、编辑。清理真实文件不可撤销，被删路径在原下载 App 中会失效。
5. 转码相似提示使用 3 个采样画面的 dHash 和接近的时长，**仅供人工对比播放**，不会自动删除或合并。可能漏报/误报；裁切、水印、不同开头会影响结果。为避免大库卡顿，单轮比较上限一百万对、提示最多100对。

## 虚拟视频与兼容性

系统文件选择器的「映库」来源、或视频菜单的分享入口，都提供只读 content URI；支持 URI 的应用可直接读取同一份真实视频。它没有复制完整视频。

这不是共享目录硬链接。Android SAF 和常见 `/storage/emulated/0` FUSE 存储不允许普通 App 对任意旧路径做透明硬链接。零字节 MP4 / 播放列表文件不能让任意旧 App 当原 MP4 使用，因此没有生成这些替身，也不承诺能阻止旧下载器再次下载。新副本会在扫描后识别。未来新 App 可以明确接入映库的 URI，避免复制。

## 读取与安全

- 按 provider authority + document ID 识别实际文档，避免交叠目录重复索引同一文档。
- 同大小才抽样，抽样一致才完整 SHA-256；抽样绝不直接用于删除。
- 自动刷新在前台每分钟探测目录，元数据无变化时跳过完整重读；手动刷新重新核验完整重复。完整哈希不跨扫描缓存作为删除依据。
- 文件不可读、读取中长度变化、删除被存储提供方拒绝时停止对应操作并提示。
- 显式确认删除；保留文件不可作为待删副本。删除前 fresh hash，不依赖旧缓存。SAF 无法对第三方同时改写提供跨文件原子事务，操作时必须暂停其他文件写入。
- 封面与索引、播放历史在本机私有目录。清除映库数据不会删除视频，但会丢失目录授权与进度。
- 首屏48项，显示更多逐批加载；递归扫描上限10000文件。超大库建议按目录拆分。

## 构建

`python3 build.py`：AAPT2 + ECJ + D8 + zipalign + apksigner；不需要 Gradle。工具路径见脚本。复用私有路径 `/workspace/shared-download-secrets` 中已授权的新签名材料；该目录严禁公开。没有私钥时无法生成可覆盖本版的后续包。

`java -jar /workspace/android-tools/ecj.jar -1.8 -proc:none -d build/tests src/org/rea/cineshelf/Dedupe.java tests/DedupeTest.java`

`java -cp build/tests DedupeTest`

`python3 build_tests.py` 生成**仅测试用** fixture/instrumentation APK，含非色情的 ffmpeg 合成视频，不会打包进正式播放器。

详细验证状态见 `VALIDATION.md`。测试版不代表在 Redmi Turbo 4 Pro 上已实测。

模拟器启动后，运行 `python3 tests/run_emulator.py` 执行全新文件流程；再强制停止 `org.rea.cineshelf`，运行 `adb shell am instrument -w org.rea.cineshelf.test/org.rea.cineshelf.test.RestoreTest` 检查独立进程恢复。软件模拟器可能需要较长的系统启动和安装时间，日志需确认 PASS 后才能记录为通过。
