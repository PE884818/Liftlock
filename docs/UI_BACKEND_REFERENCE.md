# 管理器后台接口参考

本文件仅提供现有功能和接口约束，不规定 UI 布局、控件选择、导航或技术方案。接口与范围以实际源码为准。

## 必须复用的真实接口

核心文件位于 `app/src/main/java/com/zhuanz/flymemotion/`，运动配置在 `motion-core/src/main/java/com/zhuanz/flymemotion/core/`。

- `MotionApplication.configuration()` 返回 `ConfigurationStore`。通过 `get()`、`save(MotionConfig)`、`addListener`/`removeListener`、`connected()`、`pending()`、`problem()`、`scope()`、`framework()` 读取和保存。保存异步落盘并同步现代 Xposed Remote Preferences，不能绕过这个类另建一份配置。
- `MotionConfig` 是不可变快照。字段：`enabled`、`lateDesktopAnimation`、`stiffness`、`expandDamping`、`closeDamping`、`minimumVelocityDp`、`threshold`、`blurStrength`、`revision`、`bottomRegion`。使用 `withEnabled`、`withLateDesktopAnimation`、`withBottomRegion`。修改高级参数时保留其他选择，并递增 revision。
- `ConfigCodec` JSON schema1，新增键 `lateDesktopAnimation`，旧配置缺少此键时为 false；旧 `notifications` 键被忽略且不再写出。不要恢复已删除的通知字段。
- 默认及范围：刚度200（50–800）；展开阻尼0.81、收起0.95（0.3–1.5）；速度250dp/s（50–2000）；距离0.5（0.1–0.9）；模糊1（0–1）。底部区域默认开启，高度0.15、宽度1、中心0.5；实际范围以 `BottomGestureRegion` 的校验为准。
- 恢复参数保留当前总开关与晚加载选择；故障停用仅关闭总开关，不清空用户设置。不要在首次打开 UI 时把真实配置覆盖为默认值。
- `MotionApplication.reloader()` 返回 `ProcessReloader`，调用 `reload(ReloadPlan.SYSTEMUI/SETTINGS/WALLPAPER)`，监听 `busy()`、`message()` 和变化回调。复用其会话撤销、Root 执行与加载核对，不在 Activity 主线程执行 su/ADB，不自行杀普通应用或重启手机。
- 用 `getContentResolver().call(RuntimeProvider.URI,"read",null,null)` 读取私有回执，然后 `RuntimeStatus.read(bundle,pkg,elapsedRealtime,bootCount)`。包名分别为 `com.android.systemui`、`com.android.settings`、`android`、`com.miui.miwallpaper`。四个进程的作用域名称含框架 `system`，不是把 `android` 加成普通应用。
- 晚加载回执：SystemUI 和框架进程的 `desktopAnimationState`、`desktopAnimationDetail`。状态含 `requires-framework-restart`、`framework-ready`、`uninitialized`、`waiting-launcher`、`ready`、`disabled`、`armed`、`waiting-handoff`、`played`、`cancelled`、`unsupported`、`failed`。`played` 只证明原生动画调用已送达，不证明用户视觉验收通过；未发生回调时如实显示等待。
- `RuntimeStatus.fresh/currentModule()`、错误、配置 revision、触摸/运动/壁纸绘制/场景指标继续可读。用户界面把技术指标放入诊断，普通页面用清晰中文解释状态。
- 系统设置入口仍跳转 `ManagerActivity`，Intent extra `page="lockscreen"` 必须有效。包名、RuntimeProvider URI、Remote Preferences group/key、Xposed 入口及四个作用域不能改。

