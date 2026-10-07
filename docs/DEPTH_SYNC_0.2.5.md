# 景深、统一绘制与完整覆盖提交

0.2.4 真机加载已经确认，用户反馈的前景移动、按住瞬切及闪烁不能归因于未重载。本机 KeyguardPanelViewController 的 keyguardForegroundLayer、keyguardBackgroundLayer 以及 KeyguardDepthInteractor.deductedImageView 是壁纸角色。视频景深的 TextureView 也在上述层级中，继承该组的变换。

此前整窗内容位移同时带走这些角色；0.2.5 保留各自的父节点和原生 elevation/Z，不改变景深与时钟的遮挡顺序。WallpaperLayerPose 只抵消 LockscreenPose 写入的祖先位移，用原生 alpha 乘壁纸 alpha，把屏幕 [0,0,width,Hp] 逆变换到各角色的本地裁切。嵌套角色只处理一次，避免两次淡出。暂借祖先裁切开关，使移动的父容器不把原地壁纸角色裁掉；收尾恢复，原生在期间写入的新属性不被旧值覆盖。

触摸、面板高度、弹簧与 IPC 回调先更新状态。OnPreDrawListener 从同一 MotionFrame 写内容、景深、实时镜像和原生 BackgroundBlurDrawable；Choreographer 只请求绘制。纯按住以全高帧保持覆盖，不把原生面板的暂时零高度当作拖动收起。接手动画采用最后已呈现的 rawHeight，而不是尚未绘制的动画计算值。

框架之前可在镜像覆盖提交之前准备返回窗口。现在 module 的可见性借用、客户端通知、START 绘制及祖先 Surface 显示都要求 coverCommitted。SystemUI 将新镜像先全高、alpha=1 提交到当前 ViewRoot 绘制事务，匹配会话、代次、layer 的提交回执到达后才开启准备；应用绘制和各祖先状态仍须分别核对。不会修改原生认证资格、焦点或 sleep token。

同一镜像绑定的暂时 ready=false 只阻止更多透出，保留当前覆盖并允许向下反转恢复覆盖。新会话或代次才重设全覆盖；原生认证分支始终保持锁屏壁纸。镜像替换的隐藏旧容器和显示新容器进入同一个绘制事务；提交后才释放旧包装，取消或异常包含未提交旧容器的清理。原生返回场景不就绪时仍等待后回弹。

这些代码路径解释了可能产生错位或闪烁的时序，但尚未捕获用户故障帧，不能断定每次屏幕异常的唯一原因。JVM 与模拟器验证连续轨迹、反向、回执暂失、重新接手、角色位置/alpha/裁切/层级及恢复；myron 隔离进程验证实际 OEM Drawable ABI 和同一生产角色代码。它不注册窗口、不产生手机输入，不能证明真实 SystemUI RenderThread、最终像素、AOD 或手感通过。

真机回执的 motionFrame 包含 driver=preDraw、wallpaperRoles、内容 p 与 wallpaperP；sceneReadiness 包含 coverCommitted。触摸持续期间每 500ms 提交诊断快照，方便按住时核对同一帧。sceneWorkMaxMs 本版补齐 Provider 字段，包含等锁和处理时间，不能替代实际帧数据。
