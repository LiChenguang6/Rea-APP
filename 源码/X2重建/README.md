# X2社·下载 refresh4 去广告测试版

用户反馈 refresh3 后广告再次出现，并要求冷启动默认显示“视频”。本版保留 refresh3 的弹窗和原续播路径修改，新增以下处理：

- 移除 MainActivity 中 `convertUnit()` 的启动调用，关闭底包额外远程/缓存 DEX 的静态可见启动入口。已确认该代码加载机制存在，尚未证明它是广告复发的根因。
- ARM64 首页初始化固定选择“视频”（索引1），后续手动切换标签保留。
- 下载设置显示实际版本，复制播放诊断可包含版本、进程架构与已安装 nativeLibraryDir/libapp.so 的磁盘哈希，便于区分安装版本；该磁盘哈希不证明实际映射的运行代码。

版本码34，包名 `com.example.yinqishe_shared`，沿用现有签名。可直接覆盖 refresh1/refresh2/refresh3，不要卸载或清除数据。旧 export21 不同签名的独立安装不变。

10项 ARM64 指令检查、签名和最终DEX入口检查通过。真实广告消失及源站观看仍需手机复测，不保证所有广告已消除。详见 [ADS.md](ADS.md) 和 [EXTRA-LOADER.md](EXTRA-LOADER.md)。
