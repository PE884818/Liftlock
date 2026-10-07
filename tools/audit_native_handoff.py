"""Check compiled ownership contracts; this does not assert phone visual correctness."""
import hashlib, json, subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
javap=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/javap.exe')
classes=root/'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
def bytecode(name):
    return subprocess.check_output([str(javap),'-J-Dfile.encoding=UTF-8','-classpath',str(classes),'-c','-p',
        'com.zhuanz.flymemotion.hook.'+name],timeout=30).decode('utf-8',errors='replace')
checks=[]
def check(name,condition): checks.append(dict(name=name,passed=bool(condition)))
ui=bytecode('SystemUiHooks');backdrop=bytecode('NativeLockscreenBackdrop')
module=bytecode('MotionModule');notification=bytecode('NotificationMotionHooks')
for forbidden in ('NativeSceneChannel','NativeWallpaperComposition','requestNativeExit','ServerSceneCover'):
    check('SystemUI does not use '+forbidden,forbidden not in ui)
check('SystemUI uses native backdrop','NativeLockscreenBackdrop.apply' in ui)
check('SystemUI calls native auth handoff','handOffAuthentication' in ui and 'showBouncer' in ui)
check('framework scene hooks disabled','SystemServerSceneHooks' not in module)
for forbidden in ('SurfaceControl$Builder','mirrorSurface','reparent','SurfaceControl$Transaction'):
    check('native backdrop does not use '+forbidden,forbidden not in backdrop)
check('successful exit clears owned blur','releaseClear' in backdrop)
check('notification clear participates','clearMethods' in notification and 'clearEffect' in notification)
check('native list remains scrollable','canScrollVertically' in notification)
check('native list cap projected','setMaxDisplayedNotifications' in notification)
check('hidden notification clears blur','releaseClear' in notification)
presentation=bytecode('NativeNotificationPresentation')
for required in ('EffectIntent','getCurrentsStackState','setMaxDisplayedNotifications','LIST'):
    check('notification presentation uses '+required,required in presentation)
layout=bytecode('SharedLockscreenLayout')
check('chrome native bounds leased','ViewBoundsLease.hold' in layout and 'ViewBoundsLease.release' in layout)
report=dict(result='passed' if all(c['passed'] for c in checks) else 'failed',count=len(checks),checks=checks,
    moduleSha256=hashlib.sha256((root/'app/build/outputs/apk/release/app-release.apk').read_bytes()).hexdigest(),
    phoneVisualsVerified=False)
(root/'evidence/native-handoff-0.3.4.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
assert report['result']=='passed'
