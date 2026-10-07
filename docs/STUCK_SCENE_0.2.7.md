# 0.2.6 上滑卡住的回归与 0.2.7 修复候选

## 现场证据与边界

boot 136、SystemUI PID 22946 明确加载 0.2.6。用户反馈上滑卡住、仅见背景缩放和模糊，指纹正常，并要求继续修复、不回退版本。

`runtime-0.2.6-stuck-trace.jsonl` 捕获 APP 已 STARTED、visible=true、drawn=true、壁纸 swap 和镜像提交均完成，但 ancestors=false。原始物理高度从 2471 下降到 0，呈现进度却保持 p=1、wallpaperP=1、内容 y=0。因此这次卡住不是没有进入触摸入口，而是 0.2.6 把整张锁屏的拖动绑定到了持续不满足的返回场景条件。

返回 HOME 的窗口代次 5807，对应 SurfaceFlinger 中的 `LauncherOverlayWindow:com.android.quicksearchbox`，不是 Launcher 主窗口 63。窗口现场中该附属窗口的 owner UID=10144、alpha=0，Launcher 主窗口 owner UID=10151；两者都声明 BASE_APPLICATION，单凭窗口类型无法区分。APP 分支也发生 ancestors=false，不能只用桌面选窗错误解释全部异常。

源码确认祖先缓存超过 4096 项后按插入顺序删除未借用的节点，未保留活跃子节点的祖先；手机图层编号已超过 7000，显示链仍含较早创建的节点。新回归测试复现这种淘汰会破坏完整链的问题。旧框架回执没有具体阻塞节点，无法证明每次现场祖先未就绪都是这一项淘汰所致；0.2.7 新增阻塞节点诊断，重启加载后继续核实。

## 实现

生产代码使用 `SurfaceTopology` 管理已提交的可见性和父子关系。回收保留所有仍活跃或被借用的 Surface 的完整祖先链；父链未知、隐藏或循环仍失败。Java SurfaceControl 句柄改为弱引用，并按当前真实 WindowContainer 和 copyFrom 更新，防止过期句柄指向不同图层，也避免诊断缓存持有废弃的 native 资源。

捕获 show、hide、setVisibility、apply()、apply(boolean)、apply(boolean,boolean) 与 applyAsyncUnsafe；所有包装入口都注册提交回调，嵌套调用只消耗一次待提交记录。merge 保留顺序，clear/close 清除未提交记录。可见性仍来自 SurfaceFlinger 事务提交回执，不能用 Java 的期望状态直接判定就绪。

返回主窗口必须是 Activity 同 UID、alpha>0 的 BASE_APPLICATION；完整安全窗口扫描仍保留，透明的跨进程 Launcher overlay、启动预览和附属窗口不能代替主窗口的实际绘制。

等待失败后回弹先取消旧原生 fling 的结束状态，再将重新接手偏移归零。恢复距离为零也调用原生面板高度入口，避免只更新模块变量。新增 `blocker=hidden/unknown-visibility/unknown-parent/cycle:<layer>` 回执；该字段描述观察器状态，不等于独立读取 SurfaceFlinger 像素。

框架 STATUS 添加自身版本；SystemUI 只接受当前 0.2.7 图层适配器。旧版本不进入新的覆盖接管，管理器提示重启更新框架。壁纸与设置沿用原有适配；保留原生认证、HyperMusicCover 的当前输出、局部原生模糊和已有运动参数。

## 验证与待办

构建/lint、103 项 JVM 检查、70 项模拟器管理器检查、32 项模拟器 Surface 合成以及 281 项目标接口/元数据检查通过。新增测试覆盖超过 9000 个图层后的早期祖先保留、父节点更换、未知/隐藏/循环祖先和透明跨 UID Launcher overlay 排除。myron 原生模糊隔离探针通过；该探针未注册窗口、手动位置回调，不证明真实 SystemUI 像素或图层观察器 Hook。

0.2.7 已安装并读回 APK 哈希；安装后仍是 SystemUI 0.2.6、系统框架 0.2.5。此次框架实现确实变化，重载 SystemUI 不足以加载新代码，需用户重启后核对实际版本、主窗口 layer、阻塞节点、预热 ready 与真实手势。实际跟手、清晰透出、快划、认证、AOD 和性能尚未验收。
