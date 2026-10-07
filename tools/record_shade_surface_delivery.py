"""Record completed local verification and optional verified physical installation separately."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
read = lambda name: json.loads((root / name).read_text(encoding="utf-8-sig"))
apk = root / "dist/FlymeMotionPort-0.3.2-stage2.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
audit = read("evidence/target-signatures-0.3.2.json")
manager = read("evidence/manager-tests-0.3.2.json")
assert audit["result"] == manager["result"] == "passed"
assert digest == audit["moduleSha256"]
build = (root / "evidence/build-0.3.2.txt").read_bytes().decode("utf-8-sig", errors="replace")
assert "BUILD SUCCESSFUL" in build
lint = (root / "app/build/reports/lint-results-release.txt").read_text(encoding="utf-8")
lint_errors, lint_warnings = map(int, re.search(r"(\d+) errors, (\d+) warnings", lint).groups())
assert lint_errors == 0
signature = (root / "evidence/signature-0.3.2.txt").read_text(encoding="utf-8-sig")
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
tests = [ET.parse(p).getroot() for p in (root / "motion-core/build/test-results/test").glob("TEST-*.xml")]
assert tests and all(int(t.get("errors", 0)) == int(t.get("failures", 0)) == 0 for t in tests)
core = sum(int(t.get("tests", 0)) for t in tests)
installed = False
runtime = []
packages = []
serial = None
if (root / "evidence/notification-load-0.3.2.json").exists():
    installation = read("evidence/installation.json")
    reload = read("evidence/reload-0.3.2.json")
    runtime = read("evidence/notification-load-0.3.2.json")
    ui = next(row for row in runtime if row["package"] == "com.android.systemui")
    assert digest == installation["sha256"] == reload["verifiedInstalledSha256"]
    assert ui["moduleVersion"] == "0.3.2-stage2" and ui["state"] == "loaded" and not ui["error"]
    assert ui["notificationState"] not in {"failed", "unsupported"}
    assert ui["pid"] not in set(map(str, reload["selectedPids"]))
    installed, serial, packages = True, installation["serial"], reload["reloadedPackages"]
report = {
    "version": "0.3.2-stage2", "versionCode": 38, "sha256": digest,
    "build": "passed", "signature": "v2 verified", "lintErrors": lint_errors, "lintWarnings": lint_warnings,
    "coreTests": core, "managerTests": manager["count"], "targetChecks": audit["count"],
    "installedPhysicalSerial": serial, "installedHashMatched": installed,
    "runtime": runtime, "reloadedPackages": packages, "frameworkRebootRequired": False,
    "sameLockscreenVisualsAccepted": False, "realNotificationOperationsAccepted": False,
    "phoneFramePerformanceMeasured": False, "lockWallpaperFlashResolved": False,
    "phonePixelsRead": False, "phoneTouchesInjected": False,
    "applicationOrLauncherHooksAdded": False, "scopesExpanded": False,
    "userApplicationsKilled": False, "deviceRebooted": False,
    "previousFailure": "0.3.1 native shade parent unavailable caused permanent notification adapter failure",
}
(root / "evidence/verification-0.3.2.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

def prepend(path, marker, content):
    old = path.read_text(encoding="utf-8")
    if old.startswith(marker):
        old = old.split("\n---\n\n", 1)[1]
    path.write_text(content + old, encoding="utf-8")

installation_text = "已安装手机、核对 APK 哈希并重载 SystemUI，实际驻留版本已核对" if installed else "本地 APK 和对应源码已就绪；手机 ADB 当前离线，尚未安装此版"
summary = f"""# 0.3.2 通知窗口生命周期修复

当前交付 **0.3.2-stage2 / code38**，{installation_text}。

0.3.1 用户下拉实际回执为 `Native shade parent replaced`，首帧异常停用了通知适配。壁纸预热成功和通知窗口显示 Surface 就绪是两个生命周期。本版订阅实际 ViewRoot 换面回调、保留最新进度，并在原生下一帧绘制中提交完整锁屏页面。参见 [根因、实现和验证](docs/SHADE_SURFACE_LIFECYCLE_0.3.2.md)。

{core} 项核心、{manager['count']} 项管理器/独立窗口合成、{audit['count']} 项目标接口检查通过；release/v2 签名通过，lint {lint_errors} 错误/{lint_warnings} 警告。模拟器已验证窗口销毁、壁纸换面、恢复无需第二次手势和停止期间取消。本地结果不代表手机完整视觉已验收，旧模糊锁屏壁纸闪回未宣告修好。

只重载 SystemUI 即可；壁纸 0.3.1 和框架 0.2.23 兼容，不扩大作用域、不 Hook 普通应用/Home、不录手机画面或要求反复复现。下方为历史记录，0.3.1 的加载/预热不能证明其失败手势显示成功。

---

"""
for name in ("README.md", "VERIFY.md"):
    prepend(root / name, "# 0.3.2 通知窗口生命周期修复", summary)
print(json.dumps({"version": report["version"], "sha256": digest, "checks": [core, manager["count"], audit["count"]], "installed": installed, "visualAccepted": False}))
