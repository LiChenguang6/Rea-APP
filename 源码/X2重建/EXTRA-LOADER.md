# refresh4 额外加载入口分析

在用户提供的2.4.3底包中，启动链为：

`MainActivity.onCreate → FlutterActivity.convertUnit → System.loadLibrary("echo_suppress") → JNI_OnLoad → 嵌入DEX → PurchaseVerifier.seekEntry(Application)`。

通过仿真库的解包代码恢复嵌入DEX，并在隔离Android运行时仅初始化静态字段、读取解码结果，确认以下机制：

- 查询远端SDK配置、比较版本，可从网络下载或复用加密缓存的DEX。
- 使用 `DexClassLoader` / `InMemoryDexClassLoader`，加载 `com.opencache.analytics.core.Entry` 并执行入口。
- 包含环境及调试检测，因此模拟器表现可能与手机不同。

没有取得远端 Entry 的执行内容，不能证明这个加载器创建了用户所见的广告，也不能把它定为已确认根因。所审阅加载入口中未发现账户登录或视频观看接口，但远端模块是否还提供其它附加功能无法确认；禁用后的真实播放需手机复测。

refresh4仅取消MainActivity对此入口的调用，保留super.onCreate、Flutter引擎、下载悬浮按钮安装及底包的库/assets。所有图标别名仍指向同一MainActivity。底包smali中仅发现这一处convertUnit调用；所有原生库的DT_NEEDED均无echo_suppress依赖。最终APK反编译检查也确认该调用消失。

不清除旧缓存或用户数据，不修改签名密钥，不改Flutter本体的登录、观看资格或金币逻辑。底包内解包出来的DEX、解码数据与原始输入包不随源码发布。
