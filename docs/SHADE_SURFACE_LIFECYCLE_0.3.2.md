# 0.3.2：通知窗口显示 Surface 的生命周期

用户实际下拉后仍看到原生通知页。0.3.1 的保留回执为 `notificationState=failed`，手势入口 1 次、运动帧 0 次，具体异常 `IllegalStateException: Native shade parent replaced`，见 `evidence/notification-failure-0.3.1.json`。这是进入新逻辑后首帧失败并停用适配，不是用户没有开开关。

## 根因与目标系统依据

目标 SDK37 的 `ViewRootImpl.updateSurfaceControl` 在窗口 INVISIBLE 时缓存当前 SurfaceControl 并释放客户端句柄；下一次 VISIBLE 才重新创建或从缓存复制。`setWindowStopped(true)` 还会先通知 surfaceDestroyed，再销毁显示 Surface 和 BLAST 队列。壁纸属于另一窗口，预热镜像有效并不代表通知窗口已经有可用的显示父层。

0.3.1 直接在首个运动回调中要求缓存的通知父层有效，误将原生隐藏/恢复期间的正常变化判为兼容失败，整个进程后续都回到原生通知页。依据来自本机提取的 `analysis/target/ViewRootImpl.java`，不是其他 Android 版本的假设。

## 实现

- 每次 prepare 和 pre-draw 重新取得当前 ViewRoot，订阅其原生 `SurfaceChangedCallback`。创建/换面推动下一次绘制；销毁前只隐藏、脱离模块自己的镜像，生产壁纸不动。
- 接到壁纸源时先在隐藏状态创建模块镜像，保留被替换的旧镜像，直到当前通知窗口下一帧原子替换。
- 触摸/弹簧仅更新最新 MotionFrame。通知父层尚未有效时保留待提交帧，不抛异常、不停用、不覆盖原生窗口显示生命周期。
- 在 native pre-draw 将当前帧的镜像位置、裁切、透明度及旧镜像撤销合并到 `applyTransactionOnDraw`，与该帧 View 绘制一起提交。无额外追赶动画、无固定超时猜测。
- 取消会隐藏/脱离自有镜像、废弃待提交进度；下一次 native draw 同步提交隐藏，覆盖取消前排队的旧 SHOW。不会再把取消的页面带到恢复后的窗口。
- 原始 LOCK 引擎可用、通知 Surface 有效、提交次数和实际渲染提交回调分别记录。`shared-lockscreen-open` 由当前 Surface 的 frame-commit 回调确认；不是取得壁纸源就宣称已经显示。

沿用 0.3.1 的原生锁屏完整视图复用：原时钟、通知、景深优先级、快捷入口和实时壁纸，不另造一套通知页。已解锁的通知页不设置全局 KEYGUARD，不重新锁手机；左右分区、控制中心、AOD 保持原入口。本轮不改锁屏返回场景、不增加普通应用/Home Hook 或作用域。

## 验证和交付边界

自建模拟器窗口验证生产助手：壁纸源位于独立的原生窗口之下，通知窗口真实调用 `setWindowStopped` 销毁 Surface；窗口重建后无需第二次手势就显示最新壁纸源及最后的待提交进度。还验证停止期间取消、旧会话拒绝、原控件层级、半程裁切及隐藏源镜像。没有采集手机画面或注入手机触摸。

195 项核心、145 项管理器/自建合成、430 项目标接口检查通过；release 构建、v2 签名验证通过，lint 0 错误/39 警告。新增 SDK37 Surface 生命周期接口均通过实际目标 DEX 检查。

仅需更新 SystemUI：壁纸 0.3.1 的独立消息通道和框架 0.2.23 的原协议仍兼容，无需本次重启整机。当前安装/驻留进程以 `evidence/verification-0.3.2.json` 为准。真机通知页视觉、操作及性能未被本地检查证明；旧锁屏壁纸模糊闪回仍未宣告修好。
