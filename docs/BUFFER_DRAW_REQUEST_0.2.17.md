# 0.2.17 保留返回窗口的同步缓冲请求（实际验收失败）

## 后续纠正

boot146 四进程实际加载本版，保留请求30次、重发18次已经执行，但29个真实客户端 ACK 全为空。该候选未解决等待。进一步核对目标 DEX：真实 `ViewRootImpl.handleResized` 调用的是 `reportNextDraw("resized")`，并不调用这里 fixture 操作的 `setReportNextDraw`。自建 setter 覆盖不能证明真实 resize 覆盖，这一先前推断撤回。0.2.18 移除序号保留 Hook，接入客户端自身实际缓冲完成事件。

## 证据与边界

用户报告 0.2.16 和前几版一样停在模糊锁屏。boot145 四进程加载 0.2.16，16 个一次性绘制处理器均在真实客户端上下文消费（timeouts0）；14 次客户端回传为空，7 个处理器拒绝，9 次完成回调仍未使场景就绪。末笔 expected21、received22、buffer=false。0.2.16 的队列助手没有发送（queuedSent0），不能再将队列未派发当作已经证实的原因。

目标 WindowState.reportResized 在 mBufferSeqId>mSyncSeqId 时输出 syncWithBuffers=true 并将 syncSeq 更新；随后普通原生 resize 的强制更新分支可递增 syncSeq 并输出 false。目标 ViewRootImpl.setReportNextDraw 直接覆盖 mSyncBuffer，而非保留前一笔 true。当测试直接调用 setter 时，可以覆盖同步缓冲标记；不能据此声称真实 resize 会执行这一 setter。自建 SDK37 VRI 对象实际执行这两次方法，复现标志丢失；没有挂载窗口或 View。结合期望21/实际22/空事务，覆盖是明确候选机制，未直接采集真实 resize 参数，不能说已经证明唯一原因。

独立的确定缺陷：applyWithNextDraw 处理器即使收到空事务也会被原生移除；旧版拒绝后仍保留 drawWindow，后续 ensureReturnProof 因同窗口直接返回，不可能再取得该请求的缓冲证明。这使一次失败变成持续覆盖直到5秒恢复。

## 处理

在 WindowState.reportResized 当前返回窗口入口、WMS 全局锁内，尚未接受缓冲帧且认证/覆盖/会话有效时，保持 mBufferSeqId 至少为 mSyncSeqId+1。这沿用原生 syncWithBuffers 与 BLAST 流程；接受缓冲帧后停止保持，不改其他窗口或正常应用帧。reportResized 显式反优化。

空事务、拒绝或无效 latch/fence 后，在 WM Handler 排队重新请求，避免在 native finishDrawing 内递归。每个会话/窗口/Surface 最多重发两次；身份改变按新绑定重置，过期完成回执不重试新场景。每次重新绑定使旧 fence 无法作用于新场景。没有把空事务、历史 isDrawn 或 EGL swap 降级成首帧；没有改变弹簧参数、模糊和原生认证，也没有增加等待时间。

增加4条有界绘制结果，保留真实消费/完成原因，便于以后读取已有现场，无需录屏。独立锁屏覆盖仍由有效当前缓冲和呈现证明解除；未就绪不暴露黑底。

## 验证

SDK37 自建离屏 layer 提交自有 HardwareBuffer：committed、completed、latched、有效 present fence 均为 true，没有像素读取。说明这台系统可为真实缓冲提供该回执，不能将空事务直接当作已经呈现。测试不等于真实桌面或应用。

SDK37 自建 Native WindowState 与 VRI：生产 TargetBufferDrawRequest 在已消费21的状态保持下一序号22，真实 VRI 不再收到 false 标记；该 fixture 没执行原生 reportResized，也没执行手机真实锁屏链路。

新增核心回归覆盖连续窗口更新、鉴权/覆盖/已接受帧的门控、序号单调、空事务重新绑定及旧回执拒绝。release/lint、管理器与目标 DEX 检查见 VERIFY。安装、加载、真实交接和用户视觉验收分开报告，未自动重启。
