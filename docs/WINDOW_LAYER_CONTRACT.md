# 锁屏整页与底层画面的实际边界

Flyme 原包将锁屏内容和锁屏壁纸 SurfaceView 放在统一 mz_root_container 下；控制中心另属分支。原生 LockscreenWallpaperManager 使用 Flyme WMS 特有 getLockWallpaperSurfaceRoot 并重挂该根。目标澎湃没有这个同名能力，不能只复制 View 层参数就得到同一结构。

myron 实际 NotificationShadeWindowView 是 ViewRoot 根，包含 NotificationPanelView、HyperOSKeyguardRootView 和 SharedNotificationContainer。景深前景在这一树中，锁屏背景是另一个原生壁纸窗口。`evidence/window-wallpaper-0.1.6.txt` 中锁屏 WallpaperService 的绘制 Surface shown=true，桌面 ImageWallpaper shown=false/LastHidden=true。WMG 返回外层 WindowState 句柄不会消除子 Surface 的 hidden 状态。

0.1.8 仅整页移动真实 SystemUI 窗口。已删除镜像与分支位移，没有叠加替代背景。独立壁纸背景、桌面壁纸和应用透出均未实现。目前底层仍可能看到原生锁屏壁纸；这一点必须在管理器、交付文档和验收报告中保持明确。

后续需同时处理三件事：沿原生壁纸变换获取基准并在同帧驱动背景；为真实桌面壁纸得到实际可绘制内容；按原生认证许可接到 Shell/WindowManager 的应用 Surface。KPV 的 setWallpaperZoom、calculateWallpaperMatrixArray、initDeductedImageScale 和 ViewRoot RT 事务是现有变换链。原生 KeyguardViewMediatorInjector.startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation 收到 SurfaceTransition.Params 后才交接已有应用/壁纸 Surface；提前改写 showing/goingAway 会改变认证语义，不是本项目的可接受实现。

0.1.8 当时保持 SystemUI 和 Settings 两个作用域，未扩大到 system_server，也未修改原生壁纸窗口的可见性、父子关系、认证标记或桌面动画。若确实需要额外窗口服务适配，先完成具体入口分析和可审查实现，再由用户决定范围。原生 home preview 位图只能证明静态壁纸预览，不能标为实时应用/动态壁纸透出。


## 0.1.9 的实际改动

NativeWallpaperMotion 绑定真实锁屏壁纸 WindowState leash，保存并组合原生五值矩阵，使用现有 ViewRoot 的 RT 回调让背景与完整窗口共享 y；原生动画的后续 setter 更新保存为新基准，不累计上次位移。清理时先提交外部 Surface 的复原事务，再恢复最新的原生 ViewRoot 字段。

NativeHomeBackdrop 将澎湃桌面原生预览缓冲放在静止的独立 Surface，跨父节点 setRelativeLayer(..., lockWallpaper, -1)，没有叠入跟手移动区域。缓存不在帧回调读取；一次场景固定一张缓冲，释放时隐藏并移除所属 Surface。它仅是桌面壁纸原生静态预览，实时应用与动态壁纸未实现。

当前还没有目标手机图层可见与手感验收。模拟器 7 项实际合成像素检查通过，只证明不同父节点下静止下层、真实上层位移和取消/释放恢复的组合可用。其不运行本机的 MIUI ViewRoot 扩展。当前 APK 仍只有两个原作用域；进一步实时场景分析见 LIVE_SCENE_ADAPTER.md。

## 0.1.10 当前契约（取代上一版的交付状态）

用户已授权新增 android 作用域。0.1.9 真机加载已确认；用户反馈跟手改善但露出区域黑底，回执证实桌面预览为空。正确普通手机桌面标志为 1，旧标志 4 仅适用于折叠屏；现已修正。

框架租约在短暂场景内请求原生桌面窗口及允许解锁时原本返回的应用绘制。锁屏真实壁纸临时位于应用之上、完整 SystemUI 输入窗口之下；原生层级字段不改写，清理按其最新值恢复。桌面预览改挂窗口容器，只在真实原生窗口未完成绘制时使用。收到匹配租约的实际 drawn/shown 回执后移除过渡缓冲。

实际 showing、goingAway、认证结果、sleep token、电源和焦点不改变。只对选定应用的一次可见性求值限定睡眠查询范围，原生强制隐藏/遮挡/嵌入检查继续执行，生命周期保持原生。具体守卫、通信和超时语义见 LIVE_SCENE_ADAPTER.md。0.1.10 真机仍待新作用域加载与人工验收。
