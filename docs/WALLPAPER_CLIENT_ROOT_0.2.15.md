# 0.2.15 修复 0.2.14 壁纸绑定回归

用户反馈新版上滑后整屏模糊、等待很久、背后不可见。只读现场确认四进程均已加载 0.2.14，不能归因为没有重启。

失败手势的回执为 binding=0、drawSeq=-1、mirrorCommitted=false、coverCommitted=false、homeSwap=false、lockSwap=false，返回 HOME 的窗口为 GONE。UI 弹簧结束后等待五秒，进入恢复路径。首帧呈现回调并未开始，首先断在壁纸身份校验。

## 根因与修正

Android 17 的客户端 Surface 模式中，WindowState.getSurfaceControl 是服务端父容器。WallpaperService.Engine.mSurfaceControl 是单独的客户端根，已由 WindowState.setClientSurface 登记在 mWinAnimator.mSurfaceControl；引擎 mBbqSurfaceControl 则是该根之下的 BLAST 子层。

0.2.14 错误比较了“引擎根与服务端父层”或“引擎 BLAST 子层与服务端父层”。当前手机锁屏窗口父层为 55，dumpsys 报告登记的客户端根为 WallpaperService-com.miui.miwallpaper#56，两层不同。该校验恒失败，框架拒绝发送锁屏镜像，随后受覆盖提交约束的返回窗口准备也无法启动。

本版直接核对引擎根与当前 WMS 登记的客户端根。仍要求同一个 IWindow、当前角色、正代次及当前引擎的新 swap；不接受历史绘制、错误角色、旧窗口或已替换根。新增五项回归覆盖客户端层级、传统模式、Surface 重建、身份/角色/绘制和无效层。回执加入服务端、客户端、引擎层 ID 与匹配状态，便于区分绑定失败和呈现失败。

另外，准备失败恢复时，不再在高度为零的那一刻提前完成原生 fling。取消回执延后到完整锁屏恢复帧，避免原生重新计算面板可见性时把关闭高度当作可以退出。正常解锁路径和五秒失败保护策略不改变。

## 证明边界

本版修正有目标源码、实际加载/窗口文本回执及本地回归支持。没有录屏、截取手机画面、手机触摸注入或反复要求用户复现。没有缩短等待、取消保护或将不支持的呈现接口报告为成功。

新 APK 安装与哈希匹配不证明新框架加载，也不证明真实背后画面、呈现 fence、手感和性能已通过；分别以交付时验证记录及用户实际使用反馈为准。0.2.14 必须记为实际加载后验收失败。
