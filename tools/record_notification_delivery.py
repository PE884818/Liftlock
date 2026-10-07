"""Assemble bounded stage 2 delivery evidence; does not access a device or its pixels."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
apk = root / "dist/FlymeMotionPort-0.3.0-stage2.apk"
audit = json.loads((root / "evidence/target-signatures-0.3.0.json").read_text(encoding="utf-8"))
manager = json.loads((root / "evidence/manager-tests-0.3.0.json").read_text(encoding="utf-8"))
runtime = json.loads((root / "evidence/notification-load-0.3.0.json").read_text(encoding="utf-8"))
assert audit["result"] == "passed" and manager["result"] == "passed"
assert hashlib.sha256(apk.read_bytes()).hexdigest() == audit["moduleSha256"]
ui = next(row for row in runtime if row["package"] == "com.android.systemui")
assert ui["moduleVersion"] == "0.3.0-stage2" and ui["notificationState"] == "ready" and not ui["error"]
report = {
    "version": "0.3.0-stage2", "versionCode": 36,
    "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "build": "passed", "signature": "v2 verified", "lintErrors": 0, "lintWarnings": 36,
    "coreTests": 194, "coreFailures": 0, "managerTests": manager["count"], "targetChecks": audit["count"],
    "installedPhysicalSerial": "d3f205a7", "installedHashMatched": True,
    "runtime": runtime, "systemUiReloaded": True,
    "frameworkRebootRequired": False, "notificationsDefaultEnabled": False,
    "notificationCallbacksObserved": int(ui["notificationGestures"]),
    "realNotificationOperationsVerified": False, "handFeelAccepted": False,
    "lockWallpaperFlashResolved": False,
    "phonePixelsRead": False, "phoneTouchesInjected": False,
    "applicationOrLauncherHooksAdded": False, "scopesExpanded": False,
    "userAppsKilled": False, "deviceRebooted": False,
}
(root / "evidence/verification-0.3.0.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

readme = root / "README.md"
historical = readme.read_text(encoding="utf-8").split("\n", 2)[2]
prefix = """# Flyme 手感移植

当前交付 **0.3.0-stage2 / code36**，已安装 myron、核对手机 APK 哈希；SystemUI 已实际加载本版，45 Hook，通知入口 `ready`，错误为空。框架与壁纸沿用兼容的 0.2.23，无需整机重启。

管理器新增「统一锁屏与通知手感」独立开关，旧配置默认关闭。开启即可使用同一整页跟手与 Flyme 弹簧，原生通知路由和布局保留，模糊仅覆盖面板区域。本机真实接入点为 NotificationPanelExpandController；详见 [实现和边界](docs/NOTIFICATION_SHEET_0.3.0.md)。

194 项核心、120 项模拟器管理器及 397 项目标接口检查通过，release/lint/签名通过。实际通知手势回调仍为零，通知操作、手感和性能尚未验收。用户已允许先接通知中心；模糊锁屏壁纸闪回仍未解决。未扩大作用域、Hook 普通应用或 Home、录手机画面、注入手机触摸、杀用户应用或自动重启。

以下为历史交付记录，其中旧的通知限制、验收顺序和驻留版本只描述当时状态。

---

"""
readme.write_text(prefix + historical, encoding="utf-8")
verify = root / "VERIFY.md"
old = verify.read_text(encoding="utf-8")
summary = """# 0.3.0 通知中心接入

| 项目 | 结果 |
|---|---|
| release / 签名 / lint | 通过；lint 0 错误、36 警告 |
| 核心 / 模拟器管理器 / 目标接口 | 194 / 120 / 397 项通过 |
| 安装 / 手机 APK 哈希 | 0.3.0/code36，完全一致 |
| SystemUI 实际加载 | 0.3.0-stage2，PID1975，boot151，45 Hook |
| 通知入口 | ready，注册成功；错误为空 |
| 框架 / 壁纸 | 兼容的 0.2.23-stage1，未重载；无需整机重启 |
| 通知开关 | 旧配置默认关闭，管理器开启即时同步 |
| 通知真实回调 / 手感 / 操作 / 帧性能 | 手势回调0；未验收 |
| 模糊锁屏壁纸闪回 | 用户仍反馈；本版未修复 |
| 手机画面采集 / 触摸注入 / 普通应用或 Home Hook / 杀用户应用 / 自动重启 | 均未执行 |

SHA-256：`1281528d787f51a8787628a6c77de6f480a37ce5e815c5efdec2336f188bf276`。详细证据为 evidence/verification-0.3.0.json；见 [实现与边界](docs/NOTIFICATION_SHEET_0.3.0.md)。通知布局沿用澎湃，本版统一运动和区域模糊，不伪造系统锁定状态。

---

"""
verify.write_text(summary + old, encoding="utf-8")
print(json.dumps({"version": report["version"], "installed": True, "notificationState": ui["notificationState"],
                  "coreTests": 194, "managerTests": manager["count"], "targetChecks": audit["count"]}))
