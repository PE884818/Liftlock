# 来源与许可证

本版包含 HyperMusicCover 的改编代码，整体源码按 GNU Affero General Public License version 3 提供。完整文本见 LICENSE，APK 中也包含该文本。源码包包括本模块的构建脚本、所有修改源码和所使用的上游参考文件。

HyperMusicCover：Copyright (C) 2026 zyl6932，AGPL-3.0。

- 固定版本：v0.7.10，commit `9462d23d1761e002c1241d874a78b4e4548c8c7f`。
- 上游：https://github.com/zyl6932/HyperMusicCover/tree/v0.7.10
- EngineNames.java：保留类型安全识别实现，修改包名和日志入口，用于只读识别，不写入其 pending 标志。
- WallpaperProbe.java：参考构造、Surface 生命周期捕获及原生帧请求/保活方式，改编为 WallpaperHooks.java。增加会话、代次和成功 EGL swap 回执；未复制封面替换或纹理处理路径。
- PassBlurScaleFix.java：参考 mTexScale / mDrawSfScale 校正原则，适配为仅在本模块拥有的窗口中运行、可重复执行的检查。已有 HyperMusicCover 校正结果不会被重复改变。
- 未修改的参考文件与原始 NOTICE 保存在 third_party/HyperMusicCover-v0.7.10/。

libxposed API / service 101.0.0 的许可随上游发布物提供：https://github.com/libxposed/api 与 https://github.com/libxposed/service 。API 编译桩未打包进 APK。

HyperCeiler 设置入口仅参考 Preference 注入和显式跳转思路，未复制实现源码：https://github.com/ReChronoRain/HyperCeiler/blob/main/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemsettings/HyperCeilerSettings.java

运动公式与参数参考用户提供的 Meizu 21 Flyme 12 卡刷包和本地 FlymeDemo。源码包及 APK 不包含魅族或小米原始 APK、JAR、壁纸、字体或反编译的厂商类。

0.2.18 的 SurfaceStats / ELF / JNI 连接实现为本项目编写，仅使用固定目标的接口签名、结构偏移和库哈希；未分发厂商库。静态链接 NDK 28.2.13676358 的 libc++，相关 LLVM/Android 许可证通知完整保存在 third_party/NDK-libcxx/，APK 的 third_party_licenses 资产也提供同一通知。

## 管理器界面（0.3.5-miuix1）

- miuix（compose-miuix-ui）0.9.4，YuKongA，Apache-2.0：https://github.com/compose-miuix-ui/miuix 。以 Maven 依赖使用，未修改。
- 界面结构与交互参考 HyperMusicCover v0.7.10 管理器（AGPL-3.0），包括状态卡、PageScaffold/渐进模糊顶栏、ValueSlider 和每个二级页一个 Activity 的组织方式。代码为本项目重新编写。HyperMusicCover 中这部分写法注明改编自 HyperNavBar（Apache-2.0）：https://github.com/HyperNavBar/HyperNavBar 。
- AndroidX / Jetpack Compose，Apache-2.0。Apache-2.0 全文见 APK 的 `assets/licenses/Apache-2.0.txt`。
