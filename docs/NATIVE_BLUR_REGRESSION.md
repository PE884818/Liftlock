# 0.2.3 色带和输入卡顿

0.2.3 已确认失败，用户截图不是正常模糊。截图下部每行近乎相同，支持采样边缘被延伸的判断；未采集到故障发生帧的纹理和区域事务，不能断定唯一原因。

代码确认的问题：手写 27 项模糊数组以独立效果 Surface 的局部全高为范围，与平移后的屏幕位置和 ViewRoot 原生绘制区域脱节；退出后效果 Surface 可继续覆盖到恢复回执或超时。框架恢复使用同步 apply(true)，且 Surface 可见性观察时在监视器内访问 PowerManager。历史 ANR 证明输入停顿发生，但当前堆栈不足以将 ANR 唯一归因于上述同步等待。

0.2.4 使用目标系统 BackgroundBlurDrawable，位置由原生 RenderNode.PositionUpdateListener 写入屏幕坐标，显式 crop 为 [0,0,width,H*p]，Aggregator 与 ViewRoot 同帧提交。根内容 Y 与 sheet 局部 Y 相消后，sheet 屏幕底边为 H*p；弹簧超出量不影响该底边。原始引擎及镜像输出继续实时更新。原生认证分支仍保留系统模糊。

退出立即清理 Drawable，框架采用异步源恢复事务并在提交回调确认，状态采样移到 WM 锁外；实际 AOD、应用和桌面需要重启加载后的真机验收。

探针运行生产 NativeBlurSheet，并使用本机 framework 的原生 Drawable、Aggregator 和区域序列化。窗口未注册，位置回调由探针模拟；所以通过只说明接口和坐标清理契约，不等于真实渲染效果通过。失败尝试来自隔离 app_process 的共享内存/窗口会话初始化，与实际 SystemUI 加载环境不同；最终探针采用独立共享内存和未注册 ViewRoot。
