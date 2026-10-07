# 0.3.4 已撤回，当前为 0.3.3

用户试用后明确拒绝 0.3.4 的原生退场方式，并要求回退上一版本。已使用原先保存的 0.3.3 APK 降级覆盖安装，保留配置，核对手机 APK SHA256，重载 SystemUI 并确认其实际加载 0.3.3。MiWallpaper 仍为兼容的 0.3.3，系统框架旧兼容实例未重启。

当前恢复上一版滑动期间实时透出桌面/应用的路径。此次回退不是宣布旧版闪烁或通知问题已经修好。0.3.4 的尝试、验证报告及源码归档仅为历史材料；该版的原生退场不再作为当前实现。

app 与 motion-core 工作源码已从交付的 0.3.3 源码包恢复；0.3.4 新增运行辅助类已移除，旧 APK 和源码包保留。回退过程无卸载、无配置清空、无手机录屏、无触摸注入。

当前安装 APK SHA256：`cb935a15f818bcffcf472eda04cef890846e58a04672aeea2dad1ca0759ed6d5`。

证据：`evidence/rollback-request-0.3.3.json`、`evidence/rollback-reload-0.3.3.json`、`evidence/notification-rollback-load-0.3.3.json`。
