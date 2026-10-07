"""Record final artifact and bounded process evidence; no device actions."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
read = lambda name: json.loads((root / name).read_text(encoding="utf-8-sig"))
apk = root / "dist/FlymeMotionPort-0.3.1-stage2.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
audit = read("evidence/target-signatures-0.3.1.json")
manager = read("evidence/manager-tests-0.3.1.json")
installation = read("evidence/installation.json")
reload = read("evidence/reload-0.3.1.json")
runtime = read("evidence/notification-load-0.3.1.json")
assert audit["result"] == manager["result"] == "passed"
assert digest == audit["moduleSha256"] == installation["sha256"] == reload["verifiedInstalledSha256"]
ui = next(row for row in runtime if row["package"] == "com.android.systemui")
wall = next(row for row in runtime if row["package"] == "com.miui.miwallpaper")
assert ui["moduleVersion"] == wall["moduleVersion"] == "0.3.1-stage2"
assert ui["state"] == wall["state"] == "loaded" and not ui["error"] and not wall["error"]
assert ui["notificationState"] not in {"failed", "unsupported"}
tests = [ET.parse(p).getroot() for p in (root / "motion-core/build/test-results/test").glob("TEST-*.xml")]
core = sum(int(t.get("tests", 0)) for t in tests)
assert all(int(t.get("errors", 0)) == int(t.get("failures", 0)) == 0 for t in tests)
report = {
    "version": "0.3.1-stage2", "versionCode": 37, "sha256": digest,
    "build": "passed", "signature": "v2 verified", "lintErrors": 0, "lintWarnings": 38,
    "coreTests": core, "managerTests": manager["count"], "targetChecks": audit["count"],
    "installedPhysicalSerial": "d3f205a7", "installedHashMatched": True,
    "runtime": runtime, "reloadedPackages": reload["reloadedPackages"],
    "frameworkRebootRequired": False, "notificationsDefaultEnabled": False,
    "actualNotificationGestures": int(ui["notificationGestures"]),
    "sameLockscreenVisualsAccepted": False, "realNotificationOperationsAccepted": False,
    "phoneFramePerformanceMeasured": False, "lockWallpaperFlashResolved": False,
    "phonePixelsRead": False, "phoneTouchesInjected": False,
    "applicationOrLauncherHooksAdded": False, "scopesExpanded": False,
    "userApplicationsKilled": False, "deviceRebooted": False,
}
(root / "evidence/verification-0.3.1.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

def prepend_once(path, marker, content):
    old = path.read_text(encoding="utf-8")
    if old.startswith(marker):
        old = old.split("\n---\n\n", 1)[1]
    path.write_text(content + old, encoding="utf-8")

prepend_once(root / "README.md", "# 0.3.1 完整锁屏页面复用", f"""# 0.3.1 完整锁屏页面复用

当前交付 **0.3.1-stage2 / code37**，已安装 myron、核对手机 APK 哈希；SystemUI（{ui['hooks']} Hook）与 MiWallpaper 已实际加载本版，错误为空。框架仍为兼容的 0.2.23，无需因本次改动重启整机。

用户要求通知中心呈现和锁屏相同的完整界面，0.3.0 的运动适配不足以满足此要求。本版复用原生锁屏根节点、时钟及其子树、实时 LOCK 壁纸/景深、快捷入口和通知位置；状态与系统认证分离。管理器开关为「锁屏与通知中心合一」，保留既有设置。见 [原版依据、实现和边界](docs/SAME_LOCKSCREEN_SHADE_0.3.1.md)。

{core} 项核心、{manager['count']} 项模拟器管理器/自建合成、{audit['count']} 项目标接口检查通过。真机回执状态 `{ui['notificationState']}`，实际通知手势 {ui['notificationGestures']}；完整页面视觉、操作和性能尚未验收，旧模糊锁屏壁纸闪回未宣告解决。没有录手机画面、注入手机触摸、Hook 普通应用或 Home、扩大作用域或杀用户应用。

下方为历史交付记录，不代表本版完整页面实现或当前驻留版本。

---

""")
prepend_once(root / "VERIFY.md", "# 0.3.1 完整锁屏页面复用", f"""# 0.3.1 完整锁屏页面复用

| 项目 | 结果 |
|---|---|
| release / 签名 / lint | 通过；0 错误、38 警告 |
| 核心 / 管理器与自建合成 / 目标接口 | {core} / {manager['count']} / {audit['count']} 通过 |
| 安装 / 手机 APK 哈希 | 0.3.1/code37，完全一致 |
| SystemUI 实际加载 | 0.3.1，PID{ui['pid']}，boot{ui['boot']}，{ui['hooks']} Hook |
| MiWallpaper 实际加载 | 0.3.1，PID{wall['pid']}，{wall['hooks']} Hook |
| 系统框架 | 兼容的 0.2.23，没有重启 |
| 通知入口 / 回调 | {ui['notificationState']} / 手势 {ui['notificationGestures']} |
| 完整页面视觉、通知操作、性能 | 尚未验收 |
| 旧模糊锁屏壁纸闪回 | 未宣告解决 |
| 手机画面 / 注入触摸 / 新增普通应用 Hook / 自动重启 | 均未执行 |

SHA-256：`{digest}`。完整证据为 `evidence/verification-0.3.1.json`；首次取得完整原生锁屏帧后复用布局，就绪与实际触摸回执分开记录。参见 [原版依据与实现](docs/SAME_LOCKSCREEN_SHADE_0.3.1.md)。

---

""")
print(json.dumps({"version": report["version"], "sha256": digest, "checks": [core, manager["count"], audit["count"]], "visualAccepted": False}))
