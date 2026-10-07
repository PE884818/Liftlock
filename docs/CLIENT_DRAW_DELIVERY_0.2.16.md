# 0.2.16 返回窗口重绘派发与客户端回传

0.2.15 已在 boot144 四进程实际加载，用户仍反馈整屏模糊停顿、背后不可见。保留回执显示：壁纸身份 server48/client49/engine49 匹配，mirrorCommitted、coverCommitted、visible、ancestors、drawn、resumeAck、HOME/LOCK swap 均为 true；presentation binding=3、drawSeq=-1、presented=false。因此 0.2.15 修正了壁纸绑定，却没有解决真实首帧确认，必须记为实际验收失败。

## 本版调整

目标 services.jar 的实际 DEX 显示：WindowState.reportResized 经 WindowProcessController 把 WindowStateResizeItem 放入 ClientLifecycleManager；原生布局推迟、遍历已调度或布局中时，该队列不会立即发送。独立准备路径此前只调用 reportResized，未处理队列仍待派发的情况。本版在认证允许、完整覆盖已提交、返回窗口运行且身份匹配时，调用原生 dispatchPendingTransaction，仅发送该窗口进程的已有事务，并请求原生遍历。没有向其他进程广播绘制，也没有伪造生命周期、认证或首帧成功。

客户端绘制凭据改在 WMS.finishDrawingWindow 的实际客户端入口捕获，核对当前 IWindow 与窗口、会话、绑定、Surface、序号和真实缓冲事务。在 applyWithNextDraw 处理器真正消费这笔回传时登记绘制，再等待有效呈现 fence；不再依赖内部 WindowState Hook 提前写入的线程布尔值。取消了隐式依赖，明确反优化 WMS 和 Session 的调用入口。内部方法是否被优化绕开尚无真机计数证明；这是兼容修正，不能声称已证实为此次唯一根因。

原生超时处理、空缓冲事务、旧绑定和旧窗口仍不能报告呈现成功。加入 requests、queuedSent、clients、handlers、timeouts、null、reject、complete、expected、received 计数和最后回执原因，场景结束后保留，有界长度 2048。真实失败究竟是未派发、未回传、被拒绝还是 fence 缺失，今后有各阶段回执，而非统一显示“等待首帧”。没有缩短等待或移除保护层来掩盖问题。

## 验证边界

SDK37 无像素探针创建一个新的原生 ClientLifecycleManager 和两个本地假客户端，把事务加入自己的队列，执行真实生产 TargetDrawDispatcher：目标客户端收到一笔、其他客户端仍保留、重复派发不重放。该验证没有使用手机真实应用客户端、原生窗口或像素，未执行真实锁屏手势，不能证明用户返回画面已呈现。

核心回归覆盖真实处理器登记绘制、原生超时与空事务拒绝、消费前拓扑失效、请求序号。release/lint、管理器、目标签名和 SDK37 队列探针结果以本版验证记录为准。安装、加载、真实交接和用户视觉效果继续分开报告；没有录屏、截图、手机触摸注入或自动重启。
