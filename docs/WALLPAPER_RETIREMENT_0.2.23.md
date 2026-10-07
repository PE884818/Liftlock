# 0.2.23 锁屏专属源退场

0.2.22 四核心已经在 boot150 加载。保存的 HOME 回执证明原生 RESUMED、焦点、目标/祖先层可见及前景隐藏提交，并确认 nativeTakeover=true；用户仍观察到解锁后壁纸闪回，不能归因为遗漏重启。

## 修正

SceneSurfaceState 过去仅检查 pending map 内的 Java 显隐命令。经过 Binder parcel 或 native/BLAST 合并的事务，可能含有原生 SHOW 而没有对应的 Java pending 条目。现在每次服务端提交都会为持有的退役锁屏专属源补 HIDE，包括 pending=null；原有按命令纠正与显隐提交回执仍保留。只持有当前锁屏周期的有限源，原生新锁屏、睡眠、AOD及停用继续清除。

ServerWallpaperMirror 的退役记录保存原始 exclusiveLock 角色，并在退场核对当前实际角色。成功解锁也保护未脱离过父层的锁屏专属源；共享桌面/锁屏源继续归还原生显隐。恢复结束前保留引擎角色回执，避免清空后误判共享源。

已独立确认 nativeTakeover 的正常结束不会因为临时 clientCanDismiss 或 target 失效而走取消恢复。明确新锁屏/熄屏/AOD取消优先，特别是 setKeyguardShown 之前的旧状态不能覆盖这条边界。持久 lastExitReadiness 保存退场结果与源数量，避免后续 ENDED 状态覆盖诊断。

## 证据边界

核心回归、管理器、目标签名分别验证。SceneWallpaperExitProbe 使用生产助手：构造没有 Java pending 的 parcel/merge SHOW，验证提交补隐藏；未脱离锁屏源退役、共享源排除、新锁周期清除。SDK34 模拟器通过自有64x64离屏图层采样验证不会重新露出红色锁屏源。SDK37手机只读自建事务提交/角色回执，没有屏幕采集、录屏或输入注入。

这些结果不证明手机真实壁纸闪回已经消失。APK安装、驻留进程加载和用户视觉验收分开记录。通知中心继续按用户顺序，在锁屏视觉验收后接入。
