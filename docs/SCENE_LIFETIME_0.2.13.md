# 0.2.13：输入与场景的生命周期

用户反馈 0.2.12 时钟静止稳定，但快划卡住/不跟手、壁纸消失及采样闪烁。只读回执确认 boot 141 的 SystemUI、框架、壁纸和设置都运行 0.2.12；不是以旧框架解释失败。

## 证据和根因范围

1. 保存的有界 gestureJournal 中，g12 在原始 MOVE(w=2)收到子 CANCEL，随后才 drag:owned；g13 回弹重新接手后，原始 MOVE 中的子 CANCEL 又触发 height-fling target=2608、permit=null，随后真实 UP 才统一释放。TouchDispatch 原本只保护模块的手指生命周期，却仍 chain.proceed() 放行原生取消入口；因此原生可清追踪和速度并启动另一动画。
2. RevealGate 在未 ready 时通过 withPresentationProgress 重写内容进度。框架/桌面一旦慢或撤销，就连时钟内容都冻结；首次 ready 后另加 120ms 追赶造成快划背景滞后。新版仅保留壁纸覆盖，手势/弹簧输出始终控制内容；ready 时同帧使用当前进度，不另起曲线。DOWN 在现有覆盖内发送 reveal，HOME 回弹后保持预热，原生认证资格仍决定返回内容可见性。
3. restored 回执表示框架已移除镜像。旧 NativeSceneChannel 未清 requested、expectedToken、mirror；本地 SurfaceControl.isValid 仅代表句柄存在，不代表镜像仍合成。新版逐会话清状态、交给渲染所有者撤销并重新绑定，保留手势进度；旧会话 restored 不影响新会话。针对不可见服务通道的仪器测试旧版断言失败，新版通过。
4. 只读取当前 requested 无法防止已接受解锁时仍为 true 的 LOCK 重新露出；只在重挂时 hide 也挡不住之前构建、稍后 apply 的 SHOW。新版在 HANDOFF 隐藏退役 LOCK 并保留独立句柄，限于这些 layer id 重写可见命令，提交前再次核对，包括 merge。下一次原生 lock/sleep/AOD 及关闭/故障释放；CANCEL 使用 requested OR deferred-client 保留正在原生淡出的壁纸。

## 实现边界

不改壁纸 token 可见标志、原生 Transform、封面内容、认证、焦点、控制中心和桌面动画代码。SceneSurfaceState 对新保护仅处理退役 LOCK id；未将 HOME、应用或共享祖先整体隐藏。原生 WallpaperWindowToken.updateSurfaceVisibility 调用点显式去优化，签名从目标 services.jar 核对。

保留 LockscreenPose / WallpaperLayerPose 持续属性写入，未恢复每帧归零。景深角色仍按壁纸透明度/裁切，保留原父节点和优先级。模块关闭和异常停用解除退役源保护；下一次原生锁屏重新接管壁纸。

## 本地验证及局限

131 JVM 测试覆盖快划首帧、等待/反向/越界、实际窗口取消与子取消的区别、旧触摸序列、嵌套真实取消和源恢复策略。100 SDK34 管理器检查包含通道撤销一次性交付、新会话隔离和持续 View 属性。32 SDK34 Surface 检查包含 pending 内容跟手但壁纸保持覆盖。

生产 ServerWallpaperMirror / SceneSurfaceState 在 SDK34 自建 64px 离屏源上执行：旧 0.2.12 在原生仍 requested 时交接显示红色 LOCK，新版保持绿色 HOME；提前构建的 SHOW 在真正提交前被改为 HIDE，释放保护后原生 SHOW 再正常显示红色 LOCK。没有注册 Xposed Hook，也没动手机真实窗口。SDK34 的分离镜像生产者行为与目标 SDK37 不同，所以明确不计入分离生产者或镜像替换验证，报告 detachedProducerMirrorValidated=false。不能把上述测试当作澎湃文件夹的纹理采样、AOD 或实际手感已通过。

本轮不录手机画面、不注入触摸、不反复要求用户测试。框架和 SystemUI 必须加载本版，应用内重载 SystemUI 无法更新 system_server；若已安装仍运行旧框架，需要用户自行重启。真实背景/文件夹闪烁、壁纸消失及手感待自然使用确认。
