# 当前系统的通知中心接入点

目标代码来自本机 MiuiSystemUI 的反编译。0.3.0 已加入独立通知适配器和配置开关，见 [本版实现](NOTIFICATION_SHEET_0.3.0.md)。旧的 stage-one 通知限制已解除。

NotificationPanelViewController.TouchHandler.onInterceptTouchEvent/onTouchEvent 保留原生路由：controlCenterExpanding、controlCenterInteractive、shadeSwitchController.switching、expandHelper.visible 对控制中心、卡片展开及横向切换分别有分支。通知接管应在原生确认垂直面板追踪后发生，不能复用锁屏外层触摸抢占所有通知控件。

普通通知的运动核心是 NotificationPanelExpandController.setExpandHeight/onExpandFinish；原生注入器会将 AOSP height 直接设为全开或全关，setExpandedHeight/flingToHeight 不能单独用于这个分支。0.3.0 保留原生路由和完成事件，复用 MotionFrame/弹簧参数快照。barState=0 的普通通知不启动锁屏认证、返回窗口准备或壁纸源脱离。barState=1 沿用现有锁屏认证；barState=2/过渡状态交回原生。

只在通知面板区域使用原生区域模糊，露出区域清晰。右侧控制中心、AOD、编辑锁屏、横滑、卡片展开、回复及媒体操作保留原生路由；实际共存效果尚待用户自然使用确认。通知使用专用适配器，没有将锁屏 eligible 的 barState==1 扩成任意状态。
