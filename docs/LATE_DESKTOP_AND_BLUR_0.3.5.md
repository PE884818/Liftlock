# 0.3.5-miuix4 / Hook 0.3.5-stage2：晚加载桌面动画与解锁模糊闪帧

用户反馈两个问题：开启「晚加载桌面动画」后看不出任何变化；解锁回到桌面时，桌面背景会闪一下锁屏壁纸的模糊帧。本版只修改 SystemUI 侧的 Hook。系统框架、壁纸和设置进程仍按 0.3.5-stage1 兼容运行，不需要重启手机，但需要重载一次系统界面。

## 晚加载桌面动画为什么没起作用

boot152 的只读回执和 logcat：

| 时间 | 事件 |
|---|---|
| 02:25:41.414 | `HOME_LAUNCHER_ONRESUME` 与 `UNLOCK_ANIMATION active: true`（com.miui.home） |
| 02:25:42.405 | `UNLOCK_ANIMATION active: false` |
| 02:25:42.671 | WMS 分发 `onKeyguardLockedStateChanged: false`（真正解锁） |

两次手势的间隔分别是 3281 ms（SystemUI 手势日志）、3270 ms（动画开始）和 3266 ms（解锁），三者对得上。也就是说，桌面动画在上滑一开始就播放了，并且在真正解锁之前已经结束。

触发点是 MiuiHome 自己的 onResume（Flutter `UnlockAnimState` / `launcherOnResume`）：场景在上滑时为实时透出调用了 `makeActiveIfNeeded` 来恢复 HOME。0.3.5 暂存的是 WMS 锁定状态回调，框架回执也确实显示 `played`（已回送），但这个回调并不是动画的触发源，所以没有可见效果。

## 新做法

开启「晚加载桌面动画」且返回目标是 HOME 时，SystemUI 不再发送 `reveal`，框架因此不会提前把 HOME 恢复到 RESUMED（`SceneExitPolicy.activateRenderer` 仍要求 `revealing`）。上滑时 HOME 只处于 STARTED，桌面壁纸照常透出；原生解锁后，系统按自己的生命周期恢复 HOME，图标入场动画在这时播放。

- 返回目标是应用时，照旧提前恢复，实时画面不变。
- 目标尚未确定时先不发送；确认是应用后再补发。
- 框架的锁定状态暂存不再请求：`lateDesktopAnimation` 发给框架的值固定为 false，Home 的解锁通知按原生时序到达。
- 新的纯函数 `SceneExitPolicy.requestEarlyResume` 已加入核心回归测试。

代价是开启后，上滑过程中看不到完整的桌面图标，图标要等解锁完成才入场。这正是「晚加载」的含义，管理器页脚也写明了这一点。

回执状态：

- `deferred`：本次 HOME 的提前恢复已被扣下；
- `released`：解锁完成，HOME 由系统恢复；
- `cancelled`：手势没有解锁。

`released` 只说明交接发生了，不代表动画画面已经确认。

## 解锁后桌面闪模糊帧

MIUI 的 `ViewRootImpl.setWallpaperBlur(r)` 会在 SystemUI 窗口的下一帧，对 `getAllWallpaperAnimationTarget(0)` 返回的**所有**壁纸 leash（包括桌面壁纸）设置 self blur。`BlurUtils.applyBlur` 则设置 Shade 窗口的背景模糊。

上滑期间，模块以区域模糊租约接管这两个半径，结束时恢复原生最近一次请求的值，也就是锁屏时的模糊半径。

在成功解锁的退场路径（`hidden-frame:committed` → `releaseVisuals`）上，Shade 仍然可见，这次恢复会把锁屏时的模糊半径写回去：
- 桌面壁纸被模糊；
- Shade 背后的桌面画面也被模糊；

直到原生下一次更新时才清除，看起来就是闪了一帧。

现在，确认原生已接管的解锁完成路径改为把两个半径都写 0。取消、回弹和熄屏仍恢复原生值。原生 `doBlurAndDim` 会在比较自己的缓存值后再写入，所以下一次锁屏时仍由原生重新设置。

## 验证与边界

- 核心测试 206 项通过。
- lint：0 错误，31 警告（与之前相同）。
- 模拟器 `ManagerInstrumentation`：debug 与 R8 release 各 PASS 167。

这些都不能证明手机上的动画时机和闪帧已经消失，需要用户重载系统界面后亲自确认。

本次没有录屏、没有注入触摸，也没有要求复现；只读取了已有的运行回执和 logcat。

## 0.3.5-miuix5 / Hook 0.3.5-stage3：解锁后模糊改为整段钳制

用户反馈 stage2 之后，解锁回桌面仍然会闪一帧模糊。

已确认的情况：
- SystemUI 已经加载 stage2。
- 框架回执显示，成功退场时旧的锁屏壁纸源已经被隐藏（`sources=layer=43 … exitHeld=true`）。

stage2 只在交还模糊时写 0。但 MIUI 自己的锁屏淡出动画在交还之后仍会继续请求两种模糊：
- Shade 模糊（`BlurUtils.applyBlur`）；
- 壁纸模糊（`setWallpaperSyncBlur` → `setWallpaperBlur`）。

后者会给所有壁纸图层加 self blur，其中也包括已经露出的桌面壁纸。原生流程里这段动画被锁屏淡出遮住；在本模块的流程里，桌面已经提前露出，所以能看到。

改走的链路：确认解锁完成后，模糊租约不再立即交还，而是进入钳制，持续到 `KeyguardStateController` 的 showing、goingAway、fadingAway 全部为 false，再多等两帧。

钳制期间：
- 原生的 Shade 模糊和壁纸模糊请求一律按 0 写入；
- 只记录原生请求的值。

结束时按原生在已解锁状态下的最后请求交还；若超过 1.5 s 锁屏仍未退完，就写 0。

最终画面与原生解锁完成后的状态相同，只是去掉了淡出期间作用在桌面上的模糊。

每次解锁都会在 SystemUI 手势日志里写一行 `blur:unlock-clamp end=… nativeNonZero=…`，记录钳制期间原生请求过的非零半径，供下一轮只读核对。

验证：
- 核心测试 206 项通过；
- lint：0 错误，31 警告；
- 模拟器：debug 与 R8 release 各 PASS 167。

画面仍需用户确认。

## 0.3.5-miuix6 / Hook 0.3.5-stage4：底部解锁区域改为硬保留

### 补充说明：stage3 未加载

用户反馈两点：
- 打开晚加载以后，上滑结束时桌面壁纸仍然闪一帧锁屏模糊；
- 底部区域盖住快捷方式时，在快捷方式上滑动，响应的仍是快捷方式。

只读回执显示，用户当时测试的 SystemUI 仍是 stage2：pid 27360 自 miuix5 安装后没有重载，所以 stage3 的模糊钳制还没有运行过。闪帧问题需要重载后再看。

### 快捷方式仍然响应的原因

在目标 SystemUI 里，下面这些锁屏触摸都由 `KeyguardPanelViewInjector.onTouchEvent` 处理：
- 快捷图标（`MiuiShortcutPlugin`）；
- 左右滑出的快捷页（`KeyguardMoveHelper`）；
- 双击；
- 长按编辑。

调用它的是面板自己的 `TouchHandler.onTouch`，不是子 View。

旧逻辑只有在 DOWN 时模块会话已就绪（`session.touching() && eligible()`）才保留手指。只要 `beginGesture` 提前返回，或会话判定为不可接入，这根手指就会落回原生路径，被快捷方式接走。

最近 48 条手势日志里没有出现过一次 `bottom:reserved`，说明保留确实没有生效。

### 现在的做法

只要满足下面的锁屏状态，落在区域内的 DOWN 就会被保留，不再看模块会话是否就绪：
- 锁屏处于空闲；
- 不在 AOD、认证界面、遮挡、编辑态；
- 控制中心或 QS 没有展开；
- 当前停在中间页。

被保留的整段触摸这样分流：
- 有解锁正在进行时，直接吞掉；
- 其余情况一律送入原生垂直解锁拖动（`initDownStates` 与 `handleTouch$1`）。模块能接管时使用 Flyme 手感，不能接管时就是原生上滑解锁，但不会再交给快捷方式。

另外，在 `KeyguardPanelViewInjector.onTouchEvent` 上加了第二道守卫：属于被保留手指的事件不再进入快捷图标、左右快捷页、双击和长按编辑的处理。区域外的触摸保持原样。

日志行 `bottom:reserved session=…` 会记下每次保留时模块会话是否可接管。

### 验证

- 核心测试 206 项通过；
- lint：0 错误，31 警告；
- 模拟器：debug 与 R8 release 各 PASS 167。

实际触摸行为需要重载系统界面后由用户确认。

## 0.3.5-miuix7：模拟预览外形统一；闪帧排除模糊假设

### 闪帧：模糊请求已排除

stage4 已经加载（pid 15398）。底部区域每次都成功保留了手指（`bottom:reserved session=true`）。

本次 HOME 解锁的模糊钳制记录为 `end=keyguard-gone nativeNonZero=none`：在退场期间，原生没有提出任何非零模糊请求，但用户仍然看到闪帧。因此，闪帧的来源不是 Shade 窗口模糊，也不是壁纸 self blur。

下一步需要逐帧确认是哪个图层把那一帧画了出来。`tools/trace_unlock_flash.py` 会录制 SurfaceFlinger 图层与事务轨迹：
- 只记录每一帧各图层的可见性、alpha、模糊、层级和缓冲区信息；
- 不采集屏幕像素，也不注入输入；
- 需要用户正常解锁回桌面一次。

### 模拟预览外形

新增 `ui/preview/ScreenShape.kt`，作为两个模拟手机（手感调校、底部区域）唯一的外形来源：

- **宽高**：取自 `maximumWindowMetrics` 的真实面板分辨率（竖屏）。手感调校预览不再固定使用 9:19.5。
- **圆角半径**：取自 `Display.getRoundedCorner` 按四个角分别返回的系统数值。系统没有返回时，退回 `android:dimen/rounded_corner_radius`，最后才用宽度的 9%。半径按预览宽度与真实宽度之比缩放。
- **G2 连续曲率角**：每个角由两段三次曲线过渡，加中间一段缩短的圆弧组成（平滑度 0.6），顶点处仍保持系统给出的圆弧半径。曲线衔接点已做数值核对。
- 底部区域预览的裁剪与描边使用同一路径。

模拟器新增 4 项检查：
- 宽高比等于分辨率；
- 半径来自显示设备；
- 半径与系统左上角数值一致；
- G2 外形填满屏幕矩形。

### 验证

- 核心测试 206 项通过；
- lint：0 错误，31 警告；
- 模拟器：debug 与 R8 release 各 PASS 171。

## 0.3.5-miuix8 / Hook 0.3.5-stage5：闪帧根因（图层轨迹确认）

用户解锁回桌面一次，期间用 `tools/trace_unlock_flash.py` 录了 SurfaceFlinger 图层与事务轨迹（只有图层元数据，没有像素，也没有输入）。逐帧差异保存在 `evidence/liftlock-flash-layers.txt`。

以 `closed-sheet-handoff` 为 0 点，-12 ms 那一帧里：
- 桌面壁纸 `Wallpaper BBQ wrapper#47` 消失；
- 锁屏壁纸 `Wallpaper BBQ wrapper-lock#58` 变为可见，位于桌面图标之下；
- 下一帧（-1 ms）又换了回来。

这就是用户看到的「桌面壁纸闪一下锁屏模糊帧」。

图层父链显示：
- 前一帧，锁屏 token `WallpaperWindowToken{b062db2}#43` 被本模块挂在 Offscreen Root 下，不在屏幕上；
- 闪烁那一帧，原生 `keyguardGoingAway` 建立了 `Transition Root`，把两个壁纸 token 都挂了进去：锁屏 z=0，桌面 z=-1。于是锁屏引擎的最后一帧（锁屏状态下的模糊画面）盖在了桌面壁纸上。

旧逻辑要等场景恢复时才把锁屏源退役并隐藏，比闪帧晚了约 460 ms。之前对模糊的两次修改没有命中这个原因：stage4 的钳制已经记录到 `nativeNonZero=none`，说明退场期间并没有模糊请求。

### 修改

在系统框架 `KeyguardController.keyguardGoingAway` 进入原生逻辑之前，如果本次场景满足以下条件：
- 已认证；
- 返回目标是 HOME；

就调用 `ServerWallpaperMirror.retireForNativeExit()`，在原生建立转场之前隐藏锁屏专属的壁纸源，并把它登记为退场源。

登记之后：
- 每次服务端提交都会补一次隐藏；
- 原生发出的显示命令会被过滤；
- 「分离的源必须保持可绘制」这条规则对它不再生效。

这里复用的是 0.2.23 起成功退场后就在用的退役机制，只是提前到了转场之前，并在下一次原生锁屏、熄屏或 AOD 时照常释放。此时锁屏层已经收起，屏幕上已没有对它的镜像。返回应用的路径不变。

**这一处改在系统框架里，需要重启手机才能加载。**

### 验证

- 核心测试 206 项通过；
- lint：0 错误，31 警告；
- 模拟器：debug 与 R8 release 各 PASS 171。

重启后的真机效果需要用户确认。若仍有闪烁，可以再录一次图层轨迹对比。

## miuix9（Hook stage6）：快速上滑时的闪帧

stage5 装好之后，又录了一次图层记录，其中包含 12 次解锁。11 次解锁中，锁屏壁纸在系统解锁过渡时一直保持隐藏，没有出现闪帧。

只有 1 次例外。那次上滑很快，从亮屏到松手大约 1 秒。模块收到「手势结束」后提前收尾，或者还没收到「可直接解锁」的状态，结果 `keyguardGoingAway` 里的提前隐藏被跳过了。系统于是走了一条以 `DefaultTaskDisplayArea` 为根的过渡，把锁屏壁纸 token 重新显示出来，再由 MIUI 用约 350 ms 把它淡出。屏幕上看到的，就是锁屏模糊帧盖在桌面上闪了一下。

改法只动框架：
- `keyguardGoingAway` 本身就说明认证已经完成，所以不再要求 `clientCanDismiss`/`handoff`。只要场景还在、并且回到的是桌面，就隐藏锁屏壁纸；如果 `source` 不可用，就直接隐藏锁屏 token 的 Surface。
- 如果回桌面的场景在 1.5 s 内刚刚结束，随后发生的 going-away 也照样隐藏，回执记为 `native-going-away:lock-retired-after-end`。
- 隐藏会一直保持到下一次锁屏、熄屏或模块停用，沿用原有的 exitSources 机制。

`TargetBuild`：版本改为 stage6。框架进程必须精确匹配 stage6，因此需要重启手机；SystemUI 和壁纸进程沿用 stage5，不需要重载。

## miuix10（Hook stage7）：在过渡的开始事务里隐藏

stage6 装好后又录了一次图层记录，仍然出现一帧闪：系统先把锁屏 token 挂进 `Transition Root`，在那一帧里它是可见的；下一帧它才被隐藏，挂回屏外。这说明这条解锁路径先组装好了 keyguard 过渡，之后才轮到模块隐藏锁屏壁纸，而且这时 `keyguardGoingAway` 的钩子还没有生效。

改法：钩住 `com.android.server.wm.Transition.onTransactionReady(int, Transaction)`。满足以下三个条件时，就把锁屏 token 隐藏，并把 `setVisibility(false)` 直接写进这次过渡自己的开始事务：

- 这次过渡带有 keyguard going-away 标志（`mFlags & 0x100`），或者 `isKeyguardGoingAway` 为真；
- 锁屏 token 是这次过渡的参与者；
- 回桌面的场景仍在进行，或者刚刚结束不到 1.5 s。

这样无论系统从哪个入口开始解锁，锁屏 token 都不会有可见的那一帧。回执记为 `transition-ready:lock-retired(-after-end)`。

记录里还有一次解锁走的是 `DefaultTaskDisplayArea` 过渡，期间锁屏 token 一直没有被模块挂到屏外。它可能是没有上滑、直接指纹解锁的原生路径；如果是这样，模块不会介入，锁屏壁纸仍然按 MIUI 原生方式淡出。

## miuix11 与 miuix12（Hook stage8、stage9）：解锁记录与回滚后的闪帧

**stage8：加入解锁记录**

框架把最近 12 步解锁决定写进 `gestureJournal`，内容包括：
- 场景在何时、因为什么原因结束；
- going-away 和 transition-ready 是否发生；
- 是否找到锁屏 token；
- 锁屏 token 是否被隐藏。

**这份记录查到的闪帧**

重启后共解锁 3 次，记录见 `evidence/runtime-after-flash-stage8.txt`，其中第 2 次漏掉了：
- 那次从亮屏到 going-away 不到 0.5 s；
- 场景因为「原生状态不允许场景」回滚，此时返回目标还没被允许，锁屏 token 也还没绑定；
- 随后 transition-ready 拿到的是 `lease=-1 lock=false`，所以没有隐藏锁屏 token。

另外两次都在 transition-ready 时完成了隐藏。

**stage9：框架自己判断，不依赖场景**

- 锁屏 token 不再依赖场景绑定。场景没有绑定时，直接从 `WallpaperController.mWallpaperTokens` 中挑出独占的锁屏 token。
- 回到哪里也不再依赖场景的 target。没有 target 时，用焦点根任务的栈顶 Activity 判断是不是桌面。
- 任何一次非强制结束（包括回滚）后的 1.5 s 内，只要发生 keyguard 过渡，并且回到的是桌面，就照样隐藏锁屏 token。
- 没有上滑过的原生解锁（例如直接用指纹）不在这个窗口内，模块不介入。

## miuix13（Hook stage10）：连锁屏壁纸窗口一起隐藏

stage9 上线后仍然闪了一次。解锁记录和图层记录都显示，这次走的是 `goingAway`/`ready` 的 `hidden=true` 路径，逐帧情况见 `evidence/liftlock-flash-stage9-layers.txt`：

- 810732：锁屏 token 已隐藏，并停在屏外。
- 810740：锁屏 token 被挂到 `Transition Root` 下的 transition-leash（父节点 1204），而且被设为显示。这时锁屏 BBQ 可见，桌面壁纸不可见，也就是闪的那一帧。
- 810749：锁屏 token 又被藏回屏外。

把 token 挂进去并显示出来的操作不在 system_server 里：框架的 reparent 和显示拦截都没有截到，所以是 SystemUI 里的 Shell 解锁处理器在它自己的事务里做的。框架改不了 Shell 对 token 的操作，但 Shell 只操作 token 本身，不会碰 token 下面的窗口 Surface。

改法：隐藏锁屏 token 时，把它下面每个窗口的 Surface 也一起隐藏（`exitChildren`），并且也写进过渡的开始事务。这样即使 Shell 把 token 重新显示，下面也没有内容可画。WM 不知道这些窗口 Surface 被隐藏了，所以在下一个锁屏边界（`clearExitSources`）时显式把它们恢复为显示。

## miuix14（Hook stage11）：解锁后通知中心发白、重叠

**现象**：解锁后下拉通知中心，原本显示在锁屏上的通知会发白，看起来像重叠在一起。

**只读排查**：`dumpsys activity service com.android.systemui` 显示，解锁后仍然是 `AmbientState.mIsFlingRequiredAfterLockScreenSwipeUp=true`，同时 `mStatusBarState=SHADE`。

对 SystemUI 反编译后看到：
- 原生的 `endMotionEvent` 会把这个标记设为 true；之后只有 `NotificationStackScrollLayout.setPanelFlinging(false)` 会把它清掉，而且只在仍处于锁屏时才清。`setStatusBarState` 也只在前一个状态不是 KEYGUARD 时才清。
- 模块的协调释放和原生一样设置了这个标记，但我们的甩动是在解锁之后才结束的，标记因此一直留着。
- MIUI 的 `KeyguardPanelViewController.updateVisibility` 规定：只要 `mIsFlinging && mIsFlingRequiredAfterLockScreenSwipeUp` 成立，就让锁屏面板保持可见。所以解锁后每次下拉通知中心松手甩动时，锁屏那层通知都会叠在通知中心上。

**改法**（只改 SystemUI）：在 `NotificationStackScrollLayout.setStatusBarState(0)` 之后清除这个标记，日志记为 `shade:cleared-fling-required`。原生流程走到 SHADE 时，这个标记一直是 false。

版本：stage11。SystemUI 必须精确匹配；框架仍然接受 stage10。

## miuix15（Hook stage12）：去掉所有机型和系统限制，上滑卡片加屏幕圆角

**去掉的检查。** 能不能用，由测试者自己判断。
- `TargetBuild.matches()`（机型 myron、Android 17、指定的系统增量版本）不再拦截任何流程：
  - 框架、SystemUI、壁纸、设置的 Hook 入口；
  - 原生 SurfaceStats 库；
  - 管理器的「重载进程」。
  
  它改名为 `referenceBuild()`，只在关于页和诊断里作为参照显示。
- SystemUI versionCode 和 MiWallpaper versionCode 的检查已删除。
- `libgui.so`/`libui.so` 的 SHA-256 校验已删除。
- 首页「当前系统未适配」已删除。
- 管理器会自动从 LSPosed 作用域里移除「非本模块包」，这个逻辑已删除。
- 模块各进程之间的消息校验改为 `sameProtocol`：只要都是 0.3.5 这一代就放行，不看机型和系统。

**仍然保留的检查**
- LSPosed API 101 + Remote Preferences：配置要靠它同步到系统进程。
- 各个 Hook 依赖的类和字段：缺失时 `install()` 失败，回到原生。这属于运行时的兼容性，不是事先限制。

**卡片圆角**
- 锁屏壁纸镜像的裁切加上了系统上报的屏幕底部圆角半径，数值取自 `Display.getRoundedCorner`，取不到时回退到 framework 的 dimen。
- 卡片背后的 BackgroundBlurDrawable 用同一个半径。
- 半径作用在裁切矩形上，所以底边每一帧都跟着手指走；顶部两个角正好和屏幕自身的圆角重合。
- 设备反编译显示，HyperOS 的 SurfaceFlinger 带有 `drawSmoothCorner`/`clipSmoothCorner`，本机 `persist.sys.support_window_smoothcorner=true`。所以图层圆角会按小米的连续曲率平滑圆角合成。在其他 ROM 上，这一步只是普通圆角。
- 这一步做不到的部分：系统界面自身的内容（时钟、通知、底部快捷按钮）没有额外裁切，它们本来就排在屏幕的安全区内。

## miuix16（Hook stage13）：MiWallpaper 按类型解析

开发机的壁纸 APK 上可以找到 `ImageEngineImpl#U`，测试者的版本上找不到，所以壁纸进程直接报错。现在按类型解析：

- 服务对象：`UniversalEngine` 上，类型能赋值为 `ImageEngineImpl` 的字段；
- 引擎对象：服务上类型为 `UniversalEngine` 的字段；
- EGL 辅助对象：服务上某个字段，其类型内部声明了 `EGLSurface` 字段。

只有在整体结构和开发机版本一致时，才继续使用原来的 `M`/`U`/`l` 钩子。其他版本改为挂 `EGL14.eglSwapBuffers`，通过 EGLSurface 归属到对应引擎；角色取自 `getWallpaperFlags()`。这条路径下无法请求出帧，报告里会写明。

## miuix17（Hook stage14）：只圆底部两角，可调半径

- 壁纸镜像：裁切改为 `(0, -r, W, h)`，再设置 `setCornerRadius(sc, r)`。顶部两个圆角落在屏幕外，那里没有镜像内容。本机逐角的 `setCornerRadius(tl,tr,bl,br)` 受 `set_client_drawn_corner_radii` 开关控制，开关关闭时这个调用直接不起作用，所以没有采用。SF dump 里 `VRI-NotificationShade` 的 `geomCrop` 为空，祖先层不会把这块区域裁回 y=0。
- 模糊层：`BackgroundBlurDrawable.setCornerRadius(0, 0, r, r)`。
- 半径：`MotionConfig.cornerRadiusDp`。-1 表示跟随屏幕，用 `Display.getRoundedCorner` 底部两角的最大值，没有时退回系统 dimen；自定义值范围 0–80 dp。旧配置里没有这个字段，读取时视为 -1。预设、恢复默认和其他开关都会保留这个值。
- 兼容：两种圆角调用都是按需查找的方法，找不到就不加圆角，不会让场景进入失败回退。
- 尚未做视觉验收：需要在真机上滑时确认顶部是直角、底部和屏幕圆角对齐。

## miuix18（Hook stage15）：其他 HyperOS 3/4 版本的兼容

- **解析层**：`motion-core` 的 `Members` 是纯反射，有单元测试。它提供以下几种查找：
  - 多候选签名；
  - `$N` 后缀；
  - 访问器：先找 `$$Nest$m`，再找唯一的 `access$NNN`；
  - 按声明类顺序查找；
  - 同名的全部重载；
  - 按结构识别内部类；
  - 唯一类型字段。
- **分档安装**：
  - `FeatureGate` / `Features` 负责可选块。可选块失败时，只撤掉它自己注册的钩子，并写进 `CompatLog`；回执里新增 `compat` 列。
  - deoptimize 失败只记一条备注，不再让安装失败。
  - 框架回滚时，现在也会撤掉 `SceneSurfaceState` 的 Transaction 钩子。
- **框架**：
  - `keyguardGoingAway` 支持 `(int)` 和 `(int,int)`；
  - `startedGoingToSleep` / `startedWakingUp` 的全部重载都挂上；
  - `setKeyguardShown` 支持 3 参和 2 参；
  - `setClientVisible` 先在 `WindowToken` 上找，再在 `ActivityRecord` 上找；
  - `ensureActivitiesVisible` 支持无参、4 参和 2 参；
  - `isDisplayOccluded` 作为 `isKeyguardOccluded` 的别名；
  - `getWallpaperSurface` 缺失时，按 token 的 `canShowWhenLocked` 选择图层；
  - `useClientSurface` 和 `mAlwaysSeqId` 缺失时都当作 false；
  - 呈现回执在缺少 TransactionStats 时，改用提交回调；
  - 绘制派发依次尝试单客户端、全部待发，都没有就直接发送。
- **SystemUI**：
  - `isFalseTouch` 和 `fling` 都兼容 MIUI 与 AOSP 的参数顺序；
  - `showBouncer` 依次尝试 `(String)`、`showPrimaryBouncer(boolean)`、`(boolean)`；
  - `endMotionEvent` 和 `initDownStates` 找不到访问器时，直接挂原方法；
  - `BlurUtils.applyBlur` 支持两参和三参；
  - MIUI 专有字段改为按需读取：缺失时编辑器状态视为空闲，快捷页和控制中心视为未展开；
  - 面板动画监听不再依赖 `$6`/`$8` 这样的编号，改为对所有 `AnimatorListener` 做 deoptimize。
- **框架无回执**：第一次请求 1.5 秒内没有任何框架回执，就认为框架适配器没有运行。这时释放后直接交给原生认证和退场，不再等待场景，也不做保持。
- **壁纸**：引擎类找不到时进入通用模式。挂框架的 `updateSurface`、`doVisibilityChanged` 和 `detach`，在 EGL14 统计出帧，EGLSurface 在引擎对象下两层以内查找。
- **未验证**：OS3 和其他 OS4 版本都没有实机或系统文件可以核对。参照机上，所有项都应当显示「可用」，并且使用原来的那组候选。

