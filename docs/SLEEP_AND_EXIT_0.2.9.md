# 0.2.9：熄屏覆盖、解锁交接与桌面动画时机

## 真实失败与依据

boot 138 的 SystemUI PID 5713、system_server PID 3603、壁纸 PID 5359、设置 PID 6349 均实际加载 0.2.8。用户报告锁屏转 AOD 时闪过返回场景、解锁回桌面闪黑，以及图标没有解锁飞入。因此不能把本次异常归因于旧版本仍在运行。

HOME 场景回执确认 `activity=RESUMED`、`resumeAck=true`、`inputShade=true`、`visible=true`、`ancestors=true`、`drawn=true`、`coverCommitted=true`、`sourceDetached=true`。这些确认了生命周期和合成资格，不证明每个图标像素或用户手感。

用户从桌面锁屏并解锁的原生 Flutter 日志：

| 本地时间 | 原生事件 |
|---|---|
| 20:16:34.665 | onStop 将解锁动画置为 prepare |
| 20:16:35.201 | 预热 RESUME 触发 Showing animation / UNLOCK_ANIMATION start |
| 20:16:36.192 | UNLOCK_ANIMATION end |
| 20:16:38.635–38.651 | keyguardLocked=false，实际 USER_PRESENT |

动画在实际解锁前已播放完，是“解锁时没有图标飞入”的直接时序证据。此前另一条“不可见、取消准备”属于返回 APP 的场景，不能作为 HOME 动画的主要根因。设备桌面是 native/Rust/Flutter 实现，目标 APK 没有可供普通 Java 图标 View Hook 的 DEX 定义。

## 恢复链路的缺陷及修改

原生 `KeyguardController.isKeyguardShowing` 在 goingAway 时返回 false。旧的巡检因此会在原生退出时释放会话，并恢复锁屏阶段记录的 `clientVisible=false` 和隐藏 Surface 意图；这可能在原生退出接手期间隐藏已显示的返回窗口。旧版还分别提交返回场景恢复和原始壁纸重新接入，存在覆盖顺序间隙。这些是代码确认的缺陷，尚未捕获所有真实闪黑帧的完整合成时序，不能宣称唯一根因已被证明。

0.2.9 用 `SceneExitPolicy` 区分：

- 取消、熄屏或 AOD：恢复最新原生可见性意图，把原始锁屏 token 重新接入、镜像隐藏和借用的 Surface 显隐恢复放入调用方同一次事务。
- 原生已接受解锁：解除客户端和 Surface 可见性租约，保留当前原生返回窗口，不重放锁屏期间的旧隐藏状态；不清除 keyguard 标记或合成认证成功。

提交回执只从真实 `TransactionCommittedListener` 发出，镜像为空时也必须等待调用方的事务。窗口锁内不进行同步 SurfaceFlinger 等待。

熄屏屏障在 `PhoneWindowManager.startedGoingToSleep(int,int,boolean)` 的默认显示组进入原生逻辑之前设置，撤销现有会话；`startedWakingUp(int,int,boolean)` 清除屏障。使用实际非空的组回调，未使用空的 `startedWakingUpGlobal` 方法。AOD 的 `setKeyguardShown` 也在原生遍历前撤销。屏障阻止仍未更新 interactive 缓存时的迟到预热。

SystemUI 在 `onStartedGoingToSleep` 进入原生 AOD/fade 前取消模块高度动画、恢复内容和壁纸角色。showing=false、bouncer=true 的清理也在原生回调之前执行，防止事后恢复旧基线覆盖原生新姿态。

## 原生图标与壁纸交接

HOME 预热只准备覆盖和 STARTED 客户端，不提前 RESUME。首次有效上滑通过带会话的 reveal 请求激活 HOME；继续要求原生可解锁、安全目标、完整覆盖提交及 SystemUI 输入焦点，等待实际 RESUME 回执与绘制后显示。纯按住不能触发 HOME 的该激活。普通应用继续预热 RESUME，以保留其真实持续更新画面。

HOME 回弹后结束旧场景并交回原生生命周期，使下一次准备有机会重新建立原生动画状态；是否实际收到相应 onStop/prepare 仍须日志核对。原生退出开始后归还壁纸缩放生产接口，避免原生图标退出与模块禁止的壁纸缩放形成不同的动画所有者。

当前没有逐帧控制 Flutter 图标动画，也没有伪造 USER_PRESENT。原生图标动画从首次有效上滑开始，长拖时仍可能在最终松手前结束；这版不能声称实现与最终退出进度完全一致的每个图标飞入。还需要用户手势与原生日志验证其视觉时机，并据结果继续适配。

## 验证与边界

release/lint/签名通过；114 项 JVM 检查、SDK34 管理器 81 项、Surface 实像素合成 32 项、目标 DEX/元数据 302 项通过。目标接口核对与模拟器不证明真实 OEM Hook。

`SceneRestoreProbe` 在物理 myron SDK37 的 Root 隔离 app_process 中执行实际生产 `SceneSurfaceState.restore`、`ServerWallpaperMirror.restore`。只创建自有离屏 stack 100733 的红色锁屏、绿色 HOME 和真实 mirrorSurface，不修改真实 WM 窗口或认证。

- 取消时在同一事务隐藏 Shade，仍捕获完整红色锁屏。
- 解锁交接时保持原生已显示的 HOME，不重放旧隐藏意图；交接前后捕获的像素逐字相同。
- 实际捕获 HOME 为 `ff02ff00`，记录 OEM 颜色转换后的基线，不假定输出必为 `ff00ff00`。
- 有镜像与空镜像的恢复回执都不能先于调用方 apply。

探针没有安装 Hook；不验证实际 AOD、launcher 图标、认证或手感。`NativeWallpaperComposition`、`NativeBlurSheet`、`NativeRegionalBlurLease` 与 0.2.8 源码逐字节一致，本轮没有重复 OEM 模糊探针。

0.2.9 / code 21 已安装，APK 和手机 base.apk SHA-256 相同：`5ea4cdcc3419ee1a9779056df28beff646a2606d67bc5f50d4c66e3d33232b8c`。安装后四个进程仍运行 0.2.8，需用户重启加载新的系统框架；管理器重载 SystemUI 无法更新 system_server。真实 AOD 漏画面、闪黑、图标时机、认证、底部区域与帧性能仍待验证。

原始现场日志含设备窗口或应用数据，保存在本机 evidence/runtime-*、logcat-*，不随源码包分发。交付的紧凑回执明确区分安装、旧版实际加载、隔离助手检查和新版视觉待验收。
