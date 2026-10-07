# 0.3.5-miuix1：miuix 管理器重做

本版只重做管理器 UI。锁屏运动、认证、壁纸合成、SystemUI/框架/壁纸/设置 Hook、跨进程协议、作用域与 Remote Preferences 通道都不变。`TargetBuild.MODULE_VERSION` 仍为 `0.3.5-stage1`，因此已加载 0.3.5 的进程不会被误判为旧版，安装本 APK 不需要重载或重启。

## 技术选型

- 组件库：`top.yukonga.miuix.kmp` **0.9.4**（Maven Central），使用 miuix-ui、miuix-preference、miuix-icons、miuix-blur。
- Compose BOM 2026.08.00，Kotlin Compose 插件 2.4.10，AGP 9.4.0 内置 Kotlin。组合与 HyperMusicCover v0.7.10 相同。
- **未使用 miuix-nav**：0.9.4 的 `entry<T>` 是 JVM 21 内联函数，会迫使整个模块（包括 Hook）改用 21 字节码。二级页改为 HyperMusicCover 的做法，每页一个 Activity（`PageActivity`），由系统提供澎湃 OS 的 Activity 转场和预测性返回。Java/Kotlin 均保持 17。
- release 开启 R8，只做收缩：设置了 `-dontobfuscate -dontoptimize`，并 `-keep com.zhuanz.flymemotion.**`，见下文 DEX 对比。不开 R8 时 dex 约 33 MB，而 LSPosed 会把模块 dex 读入四个系统进程；开启后 dex 为 5.7 MB（0.3.5 为 2.5 MB）。

## 界面结构（参照 HyperMusicCover）

底部导航，四页之间用弹簧切换，可在设置中改为悬浮玻璃导航栏。每页使用大标题顶栏，滚动时折叠，顶栏带渐进模糊，列表有 miuix 阻尼回弹，内容由间距 12dp 的圆角卡片组成。

- **主页**：状态卡采用 HMC/KernelSU 样式：结论、说明、出血大图标，按下有 Tilt 反馈，点按刷新。卡片右上角是总开关。存在需要处理的事项时，卡片下方出现行动卡：重载 SystemUI、首次启用步骤或停用。另有四进程接入卡（点击进入诊断），以及设置同步、SystemUI 读取版本和 LSPosed 连接状态。
- **手感**（`page=lockscreen` 直接落在此页）：
  - 总开关；
  - 手感预设：Flyme 默认、跟手、柔和、干脆，参数不匹配任何预设时显示「自定义」；
  - 手感调校、底部解锁优先区域两个入口；
  - 晚加载桌面动画开关，下方附 SystemUI 与框架两份回执的状态行。
- **设置**：
  - 重载 SystemUI、壁纸、设置三个进程，执行前确认，执行中显示进度。新进程回执到达后才显示「已加载」，150 秒内没有回执则如实提示；
  - 系统框架一行说明只能重启手机加载；
  - 恢复默认（可分别选择手感参数、底部区域）、故障停用、ADB 卸载命令；
  - 外观：主题（含 Monet）、悬浮导航栏、界面模糊；
  - 诊断详情入口。
- **关于**：版本、适配机型与本机信息、首次启用（每一步按真实作用域和回执自动打勾）、来源与许可证、已安装的相关模块提示。

### 两个交互预览

预览均使用 motion-core 的原始函数，不复制公式：

- **手感调校**：迷你锁屏可以直接上划。
  - 画面位姿来自 `MotionFrame.sample`；
  - 松手时用 `FlymeMotion.expand` 判定解锁或弹回，速度按真实屏幕高度换算；
  - 回弹动画按 `SpringCurve` 逐帧采样，与 `SystemUiHooks` 的采样方式一致；
  - 右侧的弹簧曲线随滑块实时变化，并高亮本次实际播放的曲线；
  - 页面会用文字说明判定依据，例如「快速上滑（820 ≥ 250 dp/s）→ 解锁」；
  - 开启晚加载后，模拟桌面的图标在解锁完成后才入场。
- **底部区域**：手机轮廓按本机真实宽高比绘制，高亮区域由 `BottomGestureRegion` 计算并带弹簧动画。试划时用 `contains` 判定按下点，区域内的手势再交给 `FlymeMotion.expand` 判定。

「慢滑解锁距离」显示为 `1 - threshold`，便于理解，写回的仍是原字段 `threshold`。

## 后台接口与状态措辞

- 读写只经过 `ConfigurationStore`。滑块拖动时只更新草稿，松手时保存一次；值静止 600 ms 后也会保存一次，以覆盖 TalkBack 的 setProgress。保存会保留其他字段并让 revision 加 1，打开 UI 不会写入配置。
- 主题等外观设置存放在独立的 `ui` SharedPreferences 中，与运动配置无关。
- 状态措辞只用四级：已开启/已保存 → 已同步 → 已加载 → 已回调/已触发。`played` 显示为「已把桌面动画交给系统播放，画面请自行确认」。全部文案由 `StatusModel` 纯函数生成，并有测试检查不出现「生效」「验收」。

## 验证

- motion-core 205 项测试通过；lintRelease 0 错误。
- 模拟器 m3e_test（API 34）：`ManagerInstrumentation` 160 项全部通过，分别针对 debug 构建和 **R8 release 构建**（`-PportTestBuildType=release`）运行，后者覆盖原有 Hook 回归（pose、壁纸角色与覆盖像素、窗口分发、场景诊断、通道撤销、进程标识）。新增的 UI 检查包括：
  - 开关和晚加载的持久化；
  - 预设只写映射字段；
  - 滑块写入时保留其他选择；
  - 两个预览的试划判定；
  - 未适配机型上重载不动进程；
  - 恢复默认的两个选项、故障停用只关总开关；
  - 诊断、首次启用和许可证页面；
  - `page=lockscreen` 入口；
  - 深色、Monet、字体放大 1.3× 和 2× 的截图。
- 截图见 `evidence/manager-miuix/`，结果见 `evidence/manager-miuix/manager-tests.json`。
- DEX 对比（相对 `dist/FlymeMotionPort-0.3.5-stage1.apk`，见 `evidence/manager-miuix/dex-comparison.json`）：
  - `META-INF/xposed` 三个文件逐字节一致；
  - 113 个原有模块类全部保留，类名和成员签名未改，新增类只有 `PageActivity`；
  - **字节码不完全相同**：R8 会重新编译全部模块代码，即使关闭了优化。113 个类中 28 个完全一致，85 个存在差异，主要是寄存器分配和分支偏移，另有 nest-mate 访问器、record 辅助类、lambda 合成方法改名、`List.of()` 回填；
  - 这些差异由编译器保证语义等价，并已在 R8 release 构建上实际运行原有 Hook 回归验证，但不能等同于「字节码与 0.3.5 相同」；
  - 若要求 Hook 字节码与 0.3.5 完全一致，可把 release 的 `minifyEnabled` 改回 false，代价是 dex 约 33 MB，并会被 LSPosed 读入 SystemUI、system_server、设置和壁纸四个进程。

## 测试工具的调整

- `wholeWindowInputRegression` 的像素比较改为每通道允许 ±2 的误差。新管理器在后面时，SurfaceFlinger 合成会出现 1 级色值偏差，例如 0xFE 蓝。对话框淡入完成前，检查会等待最多 2 秒。
- 节点查找会先清理 UiAutomation 缓存并遍历所有窗口，因为 miuix WindowDialog 是独立窗口。

## 未验证

- 真机上 miuix 模糊与卡片绘制的性能。模拟器使用软件 GL，界面线程每帧约 0–1 ms，但 RenderThread 每帧约 110 ms，系统设置作参照时为 27 ms，所以模拟器帧率不能代表手机。
- 真实 LSPosed 已连接、各进程回执新鲜时首页的各种状态。模拟器只能显示「未适配 / LSPosed 未连接」分支，其余分支由 StatusModel 测试覆盖。
- 手感预设在真机上的主观感受，以及澎湃 OS 上 Activity 转场的实际观感。
- miuix1 已安装到手机（myron）由用户查看，第二轮即根据其反馈。

## 第二轮（0.3.5-miuix2）

真机反馈：状态灯和「·」滥用、布局拥挤、文字堆砌。对照 Apple HIG 的 Color、Writing、Lists 三节和 MIUI 设置页，定了五条规则：颜色只表达需要处理的状态；一行只说一件事（标题 + 右侧值）；解释放进分组页脚，每组最多一段；同一句提醒只出现一次；同一个控件只放一处。

- 组件：新增 `Section`（组标题、卡片、页脚）和 `ValueRow`（右侧值，只有警告和错误上色），删除 `StatusDot`、`StatusLine`、`GroupCard`、`Footnote`。字号改用 `MiuixTheme.textStyles`。
- 文案模型：`ProcessRow` 只带状态词和必要时的一句 `note`；`connection()` 合并 LSPosed、同步、系统界面读取三行；`desktopAnimation()` 只返回一行，关闭时不显示；进程名改为纯中文；新增 `HomeAction.Enable`。
- 主页：状态卡只有结论和一句说明，图标收进卡内，不再放总开关（总开关在「手感」页，停用时首页行动行是「开启」）。LSPosed 未连接时四个进程合并为一行。
- 手感：预设标签改为「原版 / 跟手 / 柔和 / 干脆」，说明放在页脚；两个入口行右侧显示当前值。
- 调校与区域：滑块去掉逐行摘要，按组写一段页脚；判定句改成「快速上滑，820 dp/s，解锁」「慢滑 30%，不到 50%，弹回」。
- 设置：删除灰色的「系统框架」假按钮，「需要 Root」只在页脚写一次；恢复类条目只留标题，说明在确认框里。
- 关于与向导：版本只显示 0.3.5；向导去掉编号和包名。
- 测试新增：首页、手感、调校、设置页不出现「·」或「→」；未连接时进程行合并；`connection()` 在没有回执时不显示读取行。

验证：205 项核心测试；lintRelease 0 错误（31 个警告均为既有）；模拟器 `ManagerInstrumentation` 163 项，debug 与 R8 release 构建各通过一次。改版前截图保存在 `evidence/manager-miuix/before-miuix1/`。

## 改名 Liftlock（0.3.5-miuix3）

- 应用名改为 Liftlock，首页标题、关于页、诊断复制文本和 Root 授权提示随之更新。`module_description` 去掉了「·」。
- 图标改为自适应图标 `mipmap-anydpi/ic_launcher.xml`，由三层组成：
  - `ic_launcher_background`：青绿色渐变；
  - `ic_launcher_foreground`：两张卡片，按 0.8 缩放落在 66dp 安全区内；
  - `ic_launcher_monochrome`：主题图标层。
- 应用内使用的平面版本是 `drawable/ic_launcher`，采用澎湃圆角遮罩，因为 Compose 的 `painterResource` 不能绘制 adaptive-icon。
- 备选方案草稿见 `design/icon-options.html`。
- 未改动的部分：
  - `SettingsHooks` 在系统设置里注入的入口标题仍为「Flyme 手感」，修改它属于 Hook 改动，并且需要重载设置进程。
  - 包名 `com.zhuanz.flymemotion` 不变，所以 LSPosed 作用域和配置都保留。
- 验证：
  - lintRelease 0 错误；
  - 模拟器 163 项检查分别在 debug 和 R8 release 上通过；
  - 启动器截图见 `evidence/manager-miuix/launcher-icon.png`。
