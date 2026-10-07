"""Record local checks, loaded processes and visual acceptance as separate evidence."""
import hashlib,json,re
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
read=lambda name:json.loads((root/name).read_text(encoding="utf-8-sig"))
version="0.3.3-stage2"
digest=hashlib.sha256((root/"dist/FlymeMotionPort-0.3.3-stage2.apk").read_bytes()).hexdigest()
audit=read("evidence/target-signatures-0.3.3.json");manager=read("evidence/manager-tests-0.3.3.json")
assert audit["result"]==manager["result"]=="passed" and digest==audit["moduleSha256"]
assert "BUILD SUCCESSFUL" in (root/"evidence/build-0.3.3.txt").read_text(encoding="utf-8-sig",errors="replace")
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in (root/"evidence/signature-0.3.3.txt").read_text(encoding="utf-8-sig")
lint=(root/"app/build/reports/lint-results-release.txt").read_text(encoding="utf-8")
errors,warnings=map(int,re.search(r"(\d+) errors, (\d+) warnings",lint).groups());assert errors==0
tests=[ET.parse(p).getroot() for p in (root/"motion-core/build/test-results/test").glob("TEST-*.xml")]
assert tests and all(int(t.get("errors",0))==int(t.get("failures",0))==0 for t in tests)
core=sum(int(t.get("tests")) for t in tests)
installation=read("evidence/installation.json");reload=read("evidence/reload-0.3.3.json");runtime=read("evidence/notification-load-0.3.3.json")
assert digest==installation["sha256"]==reload["verifiedInstalledSha256"]
for package in ("com.android.systemui","com.miui.miwallpaper"):
    row=next(r for r in runtime if r["package"]==package)
    assert row["moduleVersion"]==version and row["state"]=="loaded" and not row["error"],row
    assert int(row["pid"]) not in reload["selectedPids"]
ui=next(r for r in runtime if r["package"]=="com.android.systemui")
assert ui["notificationState"] not in {"failed","unsupported"}
report={"version":version,"versionCode":39,"sha256":digest,"build":"passed","signature":"v2 verified",
"coreTests":core,"managerTests":manager["count"],"targetChecks":audit["count"],"lintErrors":errors,"lintWarnings":warnings,
"installedPhysicalSerial":installation["serial"],"installedHashMatched":True,"runtime":runtime,"reloadedPackages":reload["reloadedPackages"],
"frameworkRebootRequired":False,"completeNativeLockscreenPresentationImplemented":True,"landscapeIntegrationDisabled":True,
"sameLockscreenVisualsAccepted":False,"realNotificationOperationsAccepted":False,"phoneFramePerformanceMeasured":False,
"lockWallpaperFlashResolved":False,"phonePixelsRead":False,"phoneTouchesInjected":False,"applicationOrLauncherHooksAdded":False,
"scopesExpanded":False,"userApplicationsKilled":False,"deviceRebooted":False,
"referenceProject":{"repository":"https://github.com/miyupyxh/LiftArc","commit":"571e8968a1e1043c5dd4523a2516a8615ebed3c0","license":"GPL-3.0","codeCopied":False}}
(root/"evidence/verification-0.3.3.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
summary=f"""# 0.3.3 完整锁屏通知呈现

当前交付 **0.3.3-stage2 / code39**，已安装手机、核对 APK 哈希并重载 SystemUI 与 MiWallpaper，两个进程实际加载已核对。

复用原生完整锁屏的实际视图和私有通知呈现链路：通知材质、堆叠/列表/数量模式、媒体与底部控件同步，普通清除键/分区装饰恢复锁屏状态。整页拖动用屏幕坐标，保留卡片/列表操作；横屏释放呈现并使用原生通知栏。修正实际锁屏流式引擎缺少 s() 的识别错误。参考 LiftArc 的位移/景深角色原则，未复制其代码，未将它误认为桌面首帧加载方案。[实现与参考调查](docs/FULL_NOTIFICATION_PRESENTATION_0.3.3.md)。

{core} 项核心、{manager['count']} 项模拟器、{audit['count']} 项目标接口检查通过；release/v2 签名通过，lint {errors} 错误/{warnings} 警告。完整原生呈现已接入，但手机完整视觉与真实通知操作尚待用户反馈，旧锁屏模糊壁纸闪回未宣告修好。

无需重启框架；不扩大四个作用域，不 Hook Home/普通应用，不录手机画面，不注入手机触摸，不要求反复复现。下方为历史记录。

---

"""
for name in ("README.md","VERIFY.md"):
    p=root/name;old=p.read_text(encoding="utf-8")
    if old.startswith("# 0.3.3 完整锁屏通知呈现"):old=old.split("\n---\n\n",1)[1]
    p.write_text(summary+old,encoding="utf-8")
print(json.dumps({"version":version,"sha256":digest,"checks":[core,manager["count"],audit["count"]],"installed":True,"visualAccepted":False}))
