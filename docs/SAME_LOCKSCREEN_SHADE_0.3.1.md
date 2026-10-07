# 0.3.1：复用完整锁屏页面

0.3.0 仅统一通知面板的运动，未满足“通知中心和锁屏是同一页面”。0.3.1 改为复用澎湃原有锁屏视图、实时壁纸输出和锁屏通知位置。采用 Flyme 的页面组织逻辑，保留澎湃外观；没有移植 Flyme 字体、图标和资源。

## Flyme 原版依据

本项目旁的 Meizu 21 / Flyme 12 SystemUI 解包与反编译结果：

- `analysis/decompiled/SystemUI/resources/res/layout/super_notification_shade.xml`：`mz_root_container` 中已有锁屏壁纸、ClockSurfaceContainer、keyguard_root_view 和共享通知容器，不是通知中心临时仿造另一套时钟。
- `sources/com/android/systemui/keyguard/ui/viewmodel/KeyguardRootViewModel$visible$1.java`：可见条件是 `!isExpandToQsEnabled && legacyShadeExpansion > 0`，不要求实际处于锁定状态。
- `sources/com/flyme/systemui/keyguard/ui/viewmodel/DateClockViewModel.java` 的 `calculateVisibility`：横屏或经典模式 SHADE 分支会隐藏时钟；普通竖屏分离模式继续显示锁屏时钟。

因此本版分开处理页面呈现和系统认证。已解锁时通知中心上滑仅收起页面，绝不写入全局 KEYGUARD 状态或重新锁手机。控制中心仍由原生左右分区路由处理。

## 实际实现

`SharedLockscreenLayout` 从完全展开、非 AOD、非拖动的真实锁屏帧读取现有时钟、时钟子层、签名、壁纸背景/景深前景和快捷入口的呈现属性、原生通知 top padding。下拉通知页持有这些**原来的 View 对象**，不复制时钟、不移动父节点、不改变 Z 顺序。原生通知标题让位于锁屏时钟，通知仍使用原有点击、横滑、展开、回复和媒体逻辑。

`ViewPresentationLease` 保留原生后续显隐、alpha、transitionAlpha、位移及缩放意图，呈现期间只约束精确持有的 View；结束时恢复最新原生意图。所有 Hook 限定在 SystemUI 进程，普通应用和 Home 没有入口。

`NotificationWallpaper` 使用独立于认证/返回窗口准备的消息通道，从 MiWallpaper 当前 LOCK 引擎取得 Surface 元数据和新 EGL swap 回执，镜像当前输出。只显示自己的镜像，不 SHOW/HIDE/reparent 原生壁纸源，不创建静态图片兜底，保留 HyperMusicCover 的实时封面。接收者核对发送 UID/包名、会话、序号、Surface 代次及 LOCK 角色。

镜像在原有控件下方，与 ViewRoot 下一帧事务同步。壁纸保持屏幕坐标，按统一 MotionFrame 裁切、淡出；景深前景使用同一壁纸角色规则，保持优先级，不随时钟位移。时钟、快捷入口、通知随整页运动。原生背景模糊只覆盖仍被页面覆盖的区域，露出部分保持清晰。完整展开时采用与锁屏相同的 p=1 帧。

安装核对发现本机 MiWallpaper 使用独立 UID，未持有 STATUS_BAR_SERVICE；SystemUI 持有该权限。新状态接收端不要求壁纸进程持有不存在的权限，改为严格核对框架提供的实际发送 UID 和包名。发送侧仍将目标限定为 SystemUI，并要求接收者持有 STATUS_BAR_SERVICE。不会信任 Intent 中自报的 UID/包名。壁纸进程另外记录准备和已发布序号，避免只看版本忽略消息缺失。

首次取得完整原生锁屏布局后才能复用。视图重绑、尺寸改变会重新校验；缺少布局或引擎时回执明确显示 awaiting-native-lockscreen / awaiting-lock-engine，保持原生行为，不构造替代时钟或黑色等待层。管理器「锁屏与通知中心合一」开关即时同步，旧配置保持关闭。

## 验证边界

模拟器自建窗口验证生产镜像助手：隐藏生产源的镜像可见、原有控件仍在镜像上方、半程裁切和透明度、过期会话拒绝。管理器测试验证原 View 对象与子树身份、景深优先级、静止壁纸角色、原生操作及恢复；目标 DEX 审计核对本机实际接口。上述检查不能证明这台手机上的完整视觉效果、所有通知操作或性能。

本版需更新 SystemUI 和 MiWallpaper 两个进程，原有框架返回窗口协议没有变化，不需因这次改动重启整机。不扩大作用域、不录手机画面、不注入手机触摸、不杀用户应用或 Home。先前模糊锁屏壁纸偶发闪回仍未宣告解决。
