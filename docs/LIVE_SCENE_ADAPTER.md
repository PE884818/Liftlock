# 0.2.5 当前场景合成

最新适配包含 [景深、统一绘制与完整覆盖提交](DEPTH_SYNC_0.2.5.md)：壁纸角色保持原位与原生 Z 层级，和实时镜像共享透明度及屏幕裁切；时间、通知和快捷方式移动。内容、镜像、景深、原生 Drawable 在同一 pre-draw 回调提交。框架所有返回窗口/客户端/桌面壁纸可见性借用以完整覆盖提交回执为前置条件。手机当前已安装，重启加载及视觉验收尚待用户操作。

以下是 0.2.3～0.2.4 的实现历史。旧版“前景跟随根位移”、独立 effect Surface、本地模糊区域、立即恢复全覆盖及 Choreographer 直接写合成等描述已由 0.2.5 文档更新。

---

# 0.2.3 场景合成历史

当前使用 ServerWallpaperMirror 创建原生锁屏 WallpaperWindowToken 的 live mirror，并通过受限、校验发送者的异步通道传递 SurfaceControl。SystemUI 在完整覆盖帧提交后发送会话/代次/层号回执。框架收到有效回执且原生认证、锁屏 EGL 绘制就绪，才以可恢复 attachment lease 脱离原始锁屏 token。源引擎不改矩阵、裁切、alpha 或封面缓冲。

NativeWallpaperComposition 的负层容器位于 Shade UI buffer 下；镜像保持屏幕坐标，模糊 effect sheet 位于镜像上方，使用澎湃能力判断、半径和材质混色，Y=H×p−H，区域采用本地 full-height 坐标与 27-float 原生 ABI。内容 RenderNode 位移与这棵 Surface 子树分开。

SystemServerSceneHooks 在具备原生认证资格的目标调用原生 makeActiveIfNeeded，并在 keyguard 尚未 goingAway 时只阻止该目标的提前 resume 选择，使 STOPPED 客户端走原生 START、重新绘制。AppLock 判定、窗口安全限制、真实 sleep token、输入与焦点保留原生行为。HOME 还借用原生桌面壁纸窗口及祖先可见性；以 SF committed 状态核对，不能用旧 Animator.getShown 替代实际借用后的显隐。

结束时框架先用同一事务恢复最新原生源 parent、隐藏/脱离镜像，再发送 restored 回执，SystemUI 随后释放独立容器。若通道失效，1800ms 原生 lease 及 2300ms 容器清理看门狗恢复现场。旧会话或旧代次 ack 无权触发新源脱离。

真实屏幕合成与原生生命周期加载仍待新版进程验证；离屏 fixture 不证明用户手机实际手势效果。

以下是旧适配器历史记录，其中“直接裁切原始锁屏引擎”已被上述实现替换。

---

# 0.2.2 真实场景与壁纸适配

> 本文描述 0.2.2 已交付实现，真机已判定未通过。其“直接裁切/淡出原始壁纸 leash”的分工有误；重新拆解的原包依据与后续设计以 [FLYME_WALLPAPER_REANALYSIS.md](FLYME_WALLPAPER_REANALYSIS.md) 为准。源码中新增 START 适配尚未在真机加载，不计为修复完成。

本版针对 myron / Android 17 / OS4.0.0.30.XPMCNXM、SystemUI 202602260、MiWallpaper 500707000。接口或构建不匹配时不提供场景透出。当前真机状态见 VERIFY.md，以下描述实现流程，不代表已经通过真机视觉验收。

## 作用域与身份

元数据声明 SystemUI、Settings、`system` 和 MiWallpaper。LSPosed 此分支以 `system` 加载 onSystemServerStarting，框架回执依然以系统 Context 的包名 `android` 保存。旧版声明 android 未使框架适配加载。

SystemUI 与框架请求要求 STATUS_BAR_SERVICE 和共享的发件身份。框架只接受 SystemUI 请求；壁纸只接受 SystemUI 请求；壁纸回执还核对实际 MiWallpaper 包 UID 和身份。RuntimeProvider 核对报告包 UID，管理器不能伪造这三个进程的回执。

本机 SystemUI 实际 UID 为 10227，框架从 PackageManager 查询，不用 Process.SYSTEM_UID 代替 SystemUI。NotificationShade 的 mOwnerUid 也匹配实际 UID。0.2.0 使用 1000 导致广播虽送达但被拒绝，以及通知栏窗口找不到；0.2.1 同时修正两处。

## 壁纸生产者

以 HMC v0.7.10 为固定参考，ImageEngine 构造后捕获实际桌面/锁屏实例，读取 which，跟踪 UniversalEngine 的 SurfaceControl 与 BBQ Surface。Surface 重建更换代次，并清除绘制就绪。

绑定时核对 UniversalEngine 当前 EngineService，并排除预览与已替换服务；绘制回执包含原生 IWindow Binder，框架只接受当前所选 WindowState 的对应引擎。

本机 `ImageEngineImpl.U(true)` 进入原生绘制及延时 EGL 回收分支；`M(boolean)` 调用 renderer.onDrawFrame 和 `t0.a.l()`，后者成功才计为 swap。记录渲染时的 buffer layer ID，避免旧 Surface 的延迟回调被计到新代次。每 450ms 原生帧请求维持会话，结束后由原生延迟回收。不写位图、封面、纹理或 pending 标志。

回执只在会话、代次或绘制就绪变化时发送，持续帧计数由低频运行状态报告提供。亮屏相关生命周期预热现有输出。不同壁纸引擎、视频景深和更新后的厂商构建仍须另行确认适配。

## 框架场景与显隐

框架选择原生当前聚焦根任务的既有顶层 Activity，只允许当前用户、已连接进程、未 finishing、具有主窗口且无安全窗口的目标。是否具备解锁资格来自 SystemUI 的原生 mCanDismissLockScreen；不改写认证、锁屏、休眠或任务焦点状态。

会话 1800ms 超时，SystemUI 每 450ms 续期，框架每 150ms 检查原生状态。仅在所选 Activity 可见性计算内处理 DisplayContent.isSleeping 的绘制阻断，不修改真实休眠标志。

SceneSurfaceState 跟踪系统进程创建的 Surface 默认显隐、父关系和后续 show/hide/reparent 事务；合并后的事务提交到 SF 后才更新显隐回执。对所选窗口及祖先借用可见性，保留原生最新意图用于恢复；未知祖先状态不判为就绪。Android 17 使用 client surface 时只借用服务端 WindowState 容器，应用保留其 buffer 子层生命周期。

返回客户端通过 WindowToken.setClientVisible 临时获得原生窗口绘制通知；借用只在原生认证资格有效时成立，并记住后续原生显隐意图。退出前重新协调原生状态，然后恢复客户端与容器，避免回放解锁前隐藏状态。每次检查重新绑定 WallpaperController.getWallpaperSurface(true) 当前选中的锁屏窗口，锁屏与桌面 token 在会话中同时保活。

SceneReadiness 以所选目标建立 AND 判定。APP 必须自身窗口绘制、显示和祖先链就绪；HOME 还须桌面壁纸窗口与本会话引擎 swap 就绪。桌面绘制不会替代应用绘制。锁屏引擎也必须提供有效代次和 swap 回执。

取消、配置停用、租约过期或状态不符时恢复原生意图。成功解锁交接先重新协调原生可见性，再恢复表面，避免回放解锁前的隐藏状态。输入仍由 SystemUI 接收。

## 运动与合成

MotionFrame 同时表达原始高度、速度、p、springHeight、内容位移、壁纸裁切高度/透明度和模糊进度。p=1 为完整锁屏，p=0 为收起。

- 可解锁：内容 Y = -H(1-p)+springHeight，壁纸高度 Hp，alpha = clamp((p-0.02)/0.78)。
- 需要认证：内容移动按 1/8 阻尼，壁纸全高、全不透明，释放后回弹再交接原生认证。
- 弹簧 200 / 0.81 / 0.95，保留原始超出高度，仅限制传入原生面板的高度。
- 内容 p 从原始手势高度生成；RevealGate.compose 只调整 wallpaperProgress、裁切和透明度，保留内容 Y、原始高度、速度和弹性超出量。回执缺失、场景失效和壁纸失败均不冻结内容位移。
- RevealGate 仅在新场景刚就绪时连续追上覆盖进度；追上后不限制原始手势或弹簧速度。场景失效时优先恢复壁纸覆盖。
- Choreographer 按当前显示刷新驱动合成；这不等于已证明真机 120Hz 性能。

完整 NotificationShade 根保留原生布局，时间、通知、视图景深前景跟随其位移。普通冷启动不保证 AOD 矩阵回调。绑定缺少回调时等待框架返回同一 layer 的 mLastHScale、mLastVScale、mLastSurfacePosition；这些是已提交的 WM 原生几何。此回退不会永久写入唤醒矩阵缓存，因此下一次仍读取最新几何。壁纸故障不撤销下方场景准备，新手势会重新尝试；内容运动始终独立。

现有锁屏壁纸 leash 保留原生基准矩阵，进行裁切和淡出；不平移整张壁纸，不叠加桌面缓存。特殊独立 Surface / 视频景深是否随根正确同步，需要真机验收。

原生 doDeductedImageScaleAnim 是控件、桌面与锁屏缩放的共同生产入口，拥有本手势视觉时暂停该入口及 setWallpaperZoom/initDeductedImageScale，保留正常唤醒、AOD、退出的原生处理。所有权延续到释放完成和认证交接，交回原生时先回滚再执行新变换。initDeductedImageScale 的直接 leash 变换单独记录；ViewRoot 清空引用后仍按同一 layer ID 取原生基准。Surface 更换后不借用旧代次或猜测 1 倍缩放。

原生 blurUtils / WindowBlurUtils 的能力、节电状态、半径与材质混色用于壁纸下方 effect layer，区域随 Hp 变化，ratio = clamp((1-p)/0.3)。本机构建 BlurRegion ABI 为 27 floats。认证模糊不受该进度驱动。

## 重载与故障

重载请求先向三进程发送带 nonce 的撤销消息，等对应 -1 会话回执再对核验过的 PID 发 TERM。外部壁纸 crop/alpha 恢复同时合入下一次绘制并直接提交，防止停止绘制的 SystemUI 留下外部变换。系统框架不能应用内重载，初次启用须重启设备。

故障停止受影响的适配，回执记录具体原因。安全窗口、未知/未绘制返回目标保持锁屏覆盖，收起释放等待超时会回弹；不会使用静态图片冒充真实应用场景。
