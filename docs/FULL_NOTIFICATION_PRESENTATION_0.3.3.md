# 0.3.3：通知中心呈现完整原生锁屏

验收目标：竖屏下拉通知中心呈现本机完整锁屏页面，而非普通通知页叠加时钟。原生时钟、壁纸、景深、通知材质、通知堆叠/列表/数量模式、媒体和底部快捷方式共同呈现。横屏仍使用原生通知栏，控制中心和认证不改。

## 为什么之前只有部分锁屏

0.3.2 复用了实际锁屏视图和几何位置，但 NotificationContainerViewModel、NotificationMaterialStateInteractor 仍按普通通知页发出呈现状态。真实卡片的材质、堆叠和清除入口随后被原生通知栏更新覆盖。单独修改 View 外观不完整。

0.3.3 只租用原生 **私有派生呈现流**：容器的同步状态副本、材质视图状态、通知样式、呈现模式以及通知数量布局。MiuiKeyguardRepository 的原始认证/隐私状态保持原值；实际 StackScrollAlgorithm 的呈现计算期间，限定到当前 stack/ambient 的局部读取采用锁屏模式，退出计算立即恢复。真实通知数据和卡片操作仍由系统管理。

六类材质（普通卡片、焦点通知、媒体、媒体菜单、组收起按钮、组标题）调用手机原生锁屏效果和主题上下文，不复制玻璃参数。特殊自带背景的焦点通知保留自己的效果。清除键、分区标题、底部和空通知区域复用原生锁屏实际状态；关闭/旋转恢复最后的原生更新。

已接管的整页滑动使用 MotionEvent 的屏幕坐标，每个 MOVE 的增量只消费一次，不依赖 OEM 的 200px 展开阈值，也不受自己移动 View 后的局部坐标影响。时钟/空白区域允许垂直拖动接管；通知列表、卡片、媒体和底部可点击按钮保留原生子控件处理。

## 壁纸实际错误

当前手机原有 0.3.1 壁纸回执为 `KeyguardStreamImageEngineImpl#s missing`。目标 APK 中流式锁屏引擎直接继承 ImageEngineImpl，只有普通 KeyguardImageEngineImpl/桌面引擎实现 s()。流式引擎拥有自己的 show/hideKeyguardWallpaper，明确为锁屏专属源；本版对这个已校验的精确类型识别角色，不再调用不存在的方法。仍使用现有渲染器、Surface 和 EGL swap，不修改音乐封面内容。

## LiftArc 参考调查

检查版本：`571e8968a1e1043c5dd4523a2516a8615ebed3c0`，GPL-3.0。仅研究，不复制其代码到交付 APK。

- [LockBarController.java](https://github.com/miyupyxh/LiftArc/blob/571e8968a1e1043c5dd4523a2516a8615ebed3c0/app/src/main/java/com/lockbar/app/xposed/LockBarController.java)：内容跟手位移、壁纸/景深反向补偿，排除数字自身的 blur_blend_container，未变化的变换不重复写入。这些原则用于核对本项目现有壁纸角色划分，并推动本版改为屏幕坐标驱动。
- [LockBarUnlock.java](https://github.com/miyupyxh/LiftArc/blob/571e8968a1e1043c5dd4523a2516a8615ebed3c0/app/src/main/java/com/lockbar/app/xposed/LockBarUnlock.java)：释放后交给原生 keyguardDone 或 bouncer。其实现没有返回应用/桌面窗口准备、首帧呈现或壁纸源退场校验链路，无法直接作为本项目桌面未就绪/闪回问题的替代方案。

## 验证边界

本地验证原生接口签名、呈现租用的身份隔离、材质键、取消/更新/异常恢复、横屏禁用、重复回调及屏幕坐标连续性；模拟器验证私有呈现驱动通知 Android 控件、清除键拦截与恢复、窗口换面/停止期间继续或取消。模拟器不具有澎湃实际材质效果，不等于手机完整视觉验收。

安装、加载、通知呈现实际回调和用户视觉验收分别报告。旧锁屏模糊壁纸闪回未宣告修好。本版无需 framework 重启，只重载 SystemUI 与 MiWallpaper；不新增 Home/普通应用作用域，不录手机画面或注入手机触摸。
