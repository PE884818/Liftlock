# 原生快划回退：窗口输入与动画归属的根因

证据来自这台 myron 的实际 SystemUI DEX，而不是常规反编译 Java 的布尔表达式。普通 Java 输出有分支反转，核对时使用 `analysis/target/ReleaseFallback.txt` 和 `PanelFallback.java` 指令及标签；这些厂商文件不进入源码交付包。

## 已证明的代码缺陷

1. 0.1.6 的 `ReleasePath` 要求 `falsingCalls == 2`。DEX 在 Ld8～L12a 检查零高度、interactive、bouncer 和 shouldListenForFingerprintWhenUnlocked，成立时直接走 L22a，跳过 L169 的第一次 `isFalseTouch`。L2fe 仍执行最终误触检测，L3c5 仍可进入原生直接退出分支。因此一次检测是合法路径，旧版却漏接。原来“次数为 1 应当拒绝”的测试固化了错误假设。
2. 原生 `endMotionEvent` L28～L82 的进入条件包括：tracking 且超过 slop，或者释放坐标相对原生展开起点超过 slop。它允许没有 MOVE、tracking 尚未建立的 UP。旧版 `engageNativeDrag` 只接受 tracking，忽略了释放入口对这种序列的接受方式。
3. 0.1.6 使用临时展开比例、最低速度返回值和 UI 状态查询影响原方法的分支。Flyme 判定与澎湃立即退场各自拥有动画入口，依赖中间调用次序，无法形成完整释放生命周期。

这些证明了旧适配会遗漏合法路径；当前尚无逐次真机记录证明每次用户报告的快划都由上述缺陷造成。0.1.7 为剩余路径保留诊断，避免把代码修正当成用户验收。

## 当前实现

普通锁屏上滑在统一原生释放入口协调。依据手势快照、实际速度、释放位移和原生误触检查选择展开或收起，调用现有 `fling$1`、`flingToHeight` 和 `onTrackingStopped$1`。原生高度动画的更新、结束监听器、认证和窗口处理继续存在。没有调用次数条件，没有改写 `isOnKeyguard` 或 `mCanDismissLockScreen`，没有临时展开比例。

保持原生的情形：非匹配手势、取消、编辑/快捷页/QS/AOD 等守卫、原生禁止解锁、认证或锁屏退场已开始。原生误触拒绝会回弹。开始执行释放后，即使发生异常，也只清理一次，不再把同一次释放交给原方法导致重复动画或认证调用。

诊断中的 `release:coordinated` 表示协调器提交了释放，不表示已经发生视觉动画。`height-fling` / `animator:start` / `spring:start` 分别记录原生高度入口、原生时长和弹簧接入。目标已到当前位置时，原生可以不创建动画；亮屏尚未进入 Scrim 屏幕状态时，原生可能使用 1ms 时长。`native-unlock` 记录已有原生退场动画的来源，便于区分认证动画与遗漏的手势释放。不会仅凭安装了某个共存模块就判定冲突。

## 验证与下一步

主机回归覆盖快释放和零高度使用同一判定、无 MOVE 的 UP、慢拖、反向、安全锁屏 interaction type、静止释放 type 7、任一原生误触拒绝、非有限速度和诊断记录上限。目标接口由实际 DEX 定义校验。

用户在管理器重载后复现短距离快滑、慢拖回弹、反向和动画接手。若再出现快划，读取 `gestureJournal` 中对应 gesture generation 的完整事件链，确认是否未接管、守卫退出、没有剩余高度、原生 1ms 动画或其他动画来源，然后修对应路径。不会再次通过放宽误触调用次数修补。

该段记录 0.1.7 时的边界；后续图层适配及当前交付以 WINDOW_LAYER_CONTRACT.md 和 LIVE_SCENE_ADAPTER.md 为准。

## 0.1.7 真机失败后发现的生命周期错误

用户重载后的 SystemUI PID 5621、模块 0.1.7、13 个 Hook、统一释放 8 次、弹簧 10 次；人工仍报告同样问题。保存于 `evidence/runtime-0.1.7-still-fast.txt`。典型链路：

```
g9:release:coordinated -> spring:start token=9
g10:down retouch=true
g10:spring:end token=9
g10:release:native action=3 owned=true
g10:finish token=10
g10:release:native action=1 owned=false
```

这证明当前手势的 CANCEL 处理可以在实际 UP 前清掉接管。旧日志没有窗口 action/downTime 和时间戳，不能仅凭这一段判定 CANCEL 的具体来源或解释全部人工快划。

源码缺陷是确定的：旧适配在内层 handleTouch 的 UP/CANCEL 后结束 finger；flingToHeight 只要 engaged 就接入，连内层取消触发的原生回弹也可能使用当前 generation。代号防护只能拒绝旧动画 token，无法防止已经错误归到当前 token 的动画收尾。旧 running() 还以 isStarted 判定归属，原生 end listener 比模块先执行，可能在同一帧发生提前 abandon。

[AOSP ViewGroup](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/ViewGroup.java) 的 dispatchTouchEvent 在新 DOWN 清理旧 touch target，在拦截转交时也给旧子视图 CANCEL；dispatchTransformedTouchEvent 可以临时修改原 MotionEvent 的 action，再恢复。这是接收者取消，并非窗口手指已抬起。目标 NotificationShadeWindowView 的 onInterceptTouchEvent 另有对子通知列表的取消，cancelCurrentTouch$1 则明确向窗口递归发送全局 CANCEL，使用新的 downTime。这两类取消必须区分。

0.1.8 把 finger 起止放在真实窗口分发，进入时冻结 action/downTime/eventTime；内层 CANCEL 原样执行，不结束全局输入、不获取释放动画许可。真正窗口 CANCEL 仍结束手势，包含厂商递归合成的新 downTime。弹簧只接入协调器明确提交的原生 fling；lease 用 token 持续到模块 listener 结束，不依赖 isStarted 的瞬时值。旧动画结束与同代动画在窗口 UP 返回前同步结束，都不能清除仍活动的 finger。

另修正两个退出遗漏：统一收起正常到达零高度后交给原生 showBouncer，由原生决定认证或退出；原来固定为完整可见的视觉值在动画结束后按真实进度恢复。取消和回弹不提交认证，取消过的收起动画也不提交。没有改写认证状态。

Android 模拟器实际产生新 DOWN 清理旧子视图与 MOVE 拦截取消，验证其后真实 UP 只释放一次；真实窗口 CANCEL 保留原生取消语义。主机覆盖递归窗口取消、新旧 token、旧 downTime、同步结束、参数快照、取消/停用不交接认证。这些证据不证明目标手机所有快划已经解决；0.1.8 真机测试与新来源回执仍待核对。

用户又报告壁纸闪现/缩放和底层始终是锁屏壁纸。0.1.8 已删除无效镜像，取消子分支位移，改用 SystemUI 完整窗口；独立壁纸窗口仍未接入。对原生 wallpaper zoom/matrix/reset 增加有界只读来源记录，继续排查剩余闪现。不能将镜像删除或静态根因修正当作这些视觉问题已通过。


0.1.9 将独立的真实锁屏壁纸 WindowState 接到同一帧的手势位移，而非重复渲染锁屏背景。同时修正弹簧结束就立即恢复壁纸/根视图、原生退出异步稍后才到达的显示时序；原生允许解锁时保持到实际隐藏/认证回调，并设超时恢复。设备缩放重叠只能证明存在独立原生动画来源，不能单独证明所有闪现的根因；本版仍需手机人工核对。
