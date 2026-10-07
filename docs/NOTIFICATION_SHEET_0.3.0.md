# 0.3.0：原生通知面板接入

用户已允许先接通知中心；0.2.23 的模糊锁屏壁纸闪回仍未解决，本版没有将它标为通过。

本机普通通知的垂直运动入口是 NotificationPanelExpandController.setExpandHeight/onExpandFinish。旧入口梳理中的 AOSP height/fling 不是澎湃普通通知的实际运动核心；原生注入器会直接把 AOSP 高度设为全开或全关，本版没有在这个高度上盲目替换动画。

NotificationMotionHooks 观察 Shade 窗口和状态栏转发的外部触摸，但只在原生 trackingInternal 已确认垂直面板追踪后接管运动。保留原生子控件的事件、面板完成回调与 jank-monitor 收尾。barState=0、非 AOD、非锁屏退出、非控制中心或横向切换、非 heads-up/QS 覆盖分支才允许接入；不扩大锁屏 eligible 条件。

NotificationSheet 使用锁屏相同的 MotionFrame、FlymeMotion 释放判定和 SpringCurve。位移直接按手指像素距离更新，原生 200 像素 appearance 阈值仅用于维持原生面板生命周期，不能当整张屏幕的位移量。参数在手势开始时固定；动画中接手保留当前原始高度；取消回到接手时的展开状态。展开/收起默认刚度 200、阻尼 0.81/0.95。

通知、标题和其他面板内容随 Shade 根 RenderNode 一起移动。原生单独的标题展开、卡片入场和拉伸在模块持有整页运动时归为稳定姿态，松手只有一个弹簧；完成后恢复原生面板高度、appearance、追踪与显隐。收起时保留退出位移到隐藏帧提交，不在隐藏前把整页瞬间放回屏幕。

模糊沿用澎湃 BackgroundBlurDrawable、原生可用性、半径与混色。屏幕内的区域为 [0, clamp(H+rootY, 0, H)]，子层减去根位移，避免重复平移；区域外桌面/应用保持清晰。持有区域模糊时暂借 full-window/wallpaper blur，结束后恢复原生最新请求。没有创建壁纸镜像、认证会话或返回应用准备会话。

管理器新增「统一锁屏与通知手感」独立开关，旧用户配置默认关闭。开启立即同步 Remote Preferences；正在进行的手势继续使用快照。管理器分别显示入口状态、手势、运动帧和弹簧次数。notifications=true 的配置不再被 stage-one 解码器强制清零；高级参数与恢复默认均保留这个开关。

本版统一的是真实锁屏和通知面板的运动与模糊交互，布局继续采用澎湃。没有强制把解锁后的系统状态伪装为锁屏，也没有修改右侧控制中心或桌面自身动画。

验证：194 项 JVM 核心、120 项模拟器管理器/IPC/自身图层检查、397 项实际目标 DEX/APK 接口检查通过，release/lint/签名通过。已安装 myron、核对 APK SHA-256，重载一次 SystemUI 后 actual receipt 为 0.3.0-stage2 / 45 hooks / notificationState=ready / error 为空。框架和壁纸维持兼容的 0.2.23-stage1，不需整机重启。当前通知手势回调为零，首次开启后的真实操作、性能和手感尚未验收；接口与数学检查不是用户效果验收。

未扩大作用域、Hook 普通应用/Home、采集手机画面、注入手机触摸、杀用户应用或自动重启。没有要求反复复现。
