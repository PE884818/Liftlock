"""Record distinct build, device-load and unverified visual acceptance outcomes."""
import hashlib,json,re
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
read=lambda name:json.loads((root/name).read_text(encoding='utf-8-sig'))
version='0.3.4-stage2'
digest=hashlib.sha256((root/'dist/FlymeMotionPort-0.3.4-stage2.apk').read_bytes()).hexdigest()
audit=read('evidence/target-signatures-0.3.4.json');manager=read('evidence/manager-tests-0.3.4.json')
contract=read('evidence/native-handoff-0.3.4.json')
assert all(c['result']=='passed' for c in (audit,manager,contract))
assert digest==audit['moduleSha256']==contract['moduleSha256']
assert 'BUILD SUCCESSFUL' in (root/'evidence/build-0.3.4.txt').read_text(encoding='utf-8-sig',errors='replace')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in (root/'evidence/signature-0.3.4.txt').read_text(encoding='utf-8-sig')
lint=(root/'app/build/reports/lint-results-release.txt').read_text(encoding='utf-8')
errors,warnings=map(int,re.search(r'(\d+) errors, (\d+) warnings',lint).groups());assert errors==0
tests=[ET.parse(p).getroot() for p in (root/'motion-core/build/test-results/test').glob('TEST-*.xml')]
assert tests and all(int(t.get('errors',0))==int(t.get('failures',0))==0 for t in tests)
core=sum(int(t.get('tests')) for t in tests)
installation=read('evidence/installation.json');reload=read('evidence/reload-0.3.4.json')
runtime=read('evidence/notification-load-0.3.4.json')
assert digest==installation['sha256']==reload['verifiedInstalledSha256']
ui=next(r for r in runtime if r['package']=='com.android.systemui')
assert ui['moduleVersion']==version and ui['state']=='loaded' and not ui['error'],ui
assert int(ui['pid']) not in reload['selectedPids']
assert ui['notificationState'] not in {'failed','unsupported'}
assert 'native-handoff' in ui['liveScene']
wall=next(r for r in runtime if r['package']=='com.miui.miwallpaper')
assert wall['moduleVersion'] in {version,'0.3.3-stage2'} and wall['state']=='loaded' and not wall['error']
report=dict(version=version,versionCode=40,sha256=digest,build='passed',signature='v2 verified',
    coreTests=core,managerTests=manager['count'],targetChecks=audit['count'],ownershipChecks=contract['count'],
    lintErrors=errors,lintWarnings=warnings,installedPhysicalSerial=installation['serial'],installedHashMatched=True,
    runtime=runtime,reloadedPackages=reload['reloadedPackages'],frameworkRebootRequired=False,
    nativeExitOwnership=True,preUnlockWindowPreparation=False,applicationPreviewDuringDrag=False,
    notificationListUncapped=True,landscapeIntegrationDisabled=True,
    sameLockscreenVisualsAccepted=False,realNotificationOperationsAccepted=False,
    flashResolvedVisually=False,phoneFramePerformanceMeasured=False,
    phonePixelsRead=False,phoneTouchesInjected=False,applicationOrLauncherHooksAdded=False,
    scopesExpanded=False,userApplicationsKilled=False,deviceRebooted=False)
(root/'evidence/verification-0.3.4.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
summary=f'''# 0.3.4 通知呈现修正与原生解锁交接

当前交付 **0.3.4-stage2 / code40**，已安装手机并核对 APK 哈希，SystemUI 实际加载已核对。MiWallpaper 0.3.3 兼容实例保持运行，框架旧实例空闲，本版没有新场景请求，无需手机重启。

通知中心呈现原生完整 LIST 列表，不再沿用锁屏数量上限；媒体/通知材质同时跟踪 apply/clear，退出按最新原生意图恢复。可滚动列表保留自己的触摸；时钟/景深保留锁屏布局。关闭通知页清零旧全屏模糊，静止页关闭也走统一完成入口。横屏保持原生。

锁屏退场换为原生统一交接：保留内容跟手和弹簧，停止提前改桌面/应用窗口和壁纸 token。拖动期间没有应用实时预览，原生认证/退出负责真实返回画面与唯一壁纸切换。[实现与取舍](docs/NATIVE_HANDOFF_AND_NOTIFICATION_0.3.4.md)。

{core} 项核心、{manager['count']} 项模拟器、{audit['count']} 项目标接口、{contract['count']} 项编译产物控制权检查通过；v2 签名通过，lint {errors} 错误/{warnings} 警告。手机视觉和真实通知操作未验收，不宣告闪烁彻底修好。不录手机画面、不注入手机触摸、不要求反复复现、不 Hook Home/普通应用。下方为历史记录。

---

'''
for name in ('README.md','VERIFY.md'):
    p=root/name;old=p.read_text(encoding='utf-8')
    if old.startswith('# 0.3.4 通知呈现修正与原生解锁交接'):old=old.split('\n---\n\n',1)[1]
    p.write_text(summary+old,encoding='utf-8')
print(json.dumps(dict(version=version,checks=[core,manager['count'],audit['count'],contract['count']],installed=True,uiLoaded=True,visualAccepted=False)))
