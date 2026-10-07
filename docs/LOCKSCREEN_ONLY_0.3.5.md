# 0.3.5：移除二合一，增加晚加载桌面动画

基于已回退的 0.3.3 锁屏过渡，保留滑动期间的真实桌面/应用透出，未采用被拒绝的 0.3.4 原生退场。

已删除通知中心的 Hook 注册、材质/列表/锁屏视图复用、独立壁纸呈现通道和管理器开关；对应仅服务二合一的源码/测试已删除。旧配置 `notifications=true` 不再写出或激活任何功能。锁屏本身的原生通知、认证、模糊与景深仍由锁屏过渡处理；解锁状态通知栏和控制中心不参与移植。

新增 `MotionConfig.lateDesktopAnimation` 与 `withLateDesktopAnimation(boolean)`，JSON 键同名，旧配置默认 false。管理器添加“晚加载桌面动画”。本轮按“保留滑动透出，仅延后桌面入场动画，返回应用不变”实现。

`LateDesktopAnimationHooks` 只在 SystemUI 监听系统已经绑定的原生 Launcher 动画代理。仅当前启用的锁屏手势、实际 HOME 返回目标与原生 supported-launcher 判定同时成立，才暂存 prepare/play。真实场景原生接管、锁屏隐藏帧提交、框架恢复回执及镜像释放完成后，按原始参数调用 prepare/play 一次；不操纵返回窗口加载，不 Hook 桌面或普通应用，不生成 USER_PRESENT 或绕过认证。

默认关闭完全透传。取消、重绑、旧手势回执、接口不支持、Binder 断开均不会延后认证或窗口绘制。动画调用超过3秒未取得完整交接时撤销队列并要求原生代理恢复正常显示；这个超时仅回收动画调用，不能作为真实画面就绪证据，也不重锁手机。不同 native 动画路径仍可能没有此代理回调，管理器显示等待/不支持，不能声称动画已播放。

核心回归验证开关默认、应用排除、原生交接前不播放、交接后一次播放、迟到 play、旧回执、取消/超时、手势参数快照及通知栏排除。模拟器验证旧配置迁移、新开关持久化及高级参数修改保留选择；目标 APK 核对原生代理方法与删除的类不在交付 DEX。

`desktopAnimationState=played` 是原生调用送达回执，手机动画视觉效果仍需用户确认。旧锁屏壁纸闪回问题本轮未宣告解决。Claude 管理器 UI 的自足交接提示词见 `CLAUDE_UI_HANDOFF.md`。

## 本机 Flutter 桌面适配与交付证据

本机 MiuiHome 没有注册 legacy LauncherUnlockAnimationController，初版 SystemUI 代理适配回执为 waiting-launcher，不能宣称生效。最终修正版新增 `LateHomeLockedStateHooks`，仅在当前 HOME 返回场景、原生可解锁、固定会话开关开启时，暂存该 HOME UID 在 WMS 注册的 unlocked=false 回调。locked=true、其他 UID 和普通应用返回原样传递；不 Hook Home/普通应用，不改变实际 keyguard 状态。

目标通过 `ActivityRecord.isActivityTypeHome/getUid` 确认；回调 Binder 通过原生 WMS add/remove 捕获。完成场景原子恢复提交后在 WM 锁外回送原生通知；已完成场景的迟到回调直接传递。取消或3秒动画通知等待结束时先核对原生锁定状态，已重新锁定、Binder 失效或旧会话的通知不回放。此超时只解除通知延后，不等待返回窗口绘制，也不作为就绪证据。

默认关闭不拦截回调。UI 将固定手势选择通过现有有序场景通道发给框架；若已绑定 legacy 代理，框架适配不开启，避免重复延迟。旧兼容框架仍可提供实时预览，新选项显示 requires-framework-restart。

最终本地：205 项核心测试（包含 HOME UID 排除、锁定通知透传、旧提交/续租、迟到通知、取消和新周期），122 项模拟器检查，457 项精确目标 DEX/作用域检查通过；v2 签名通过，lint 0错误41警告。最终 APK 哈希 a4c09fa5ebb01c575dd92818f5f54c9c52b749028e3022dcf0ec8fabe64de51e。重启前相同版本号的 SystemUI 曾加载初始代理构建，其 APK 与最终构建不同，不能据此宣称新增框架适配已加载。
