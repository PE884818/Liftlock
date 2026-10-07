"""One-time source migration; preserves the existing touch/release coordinator."""
from pathlib import Path
import re
p=Path(__file__).resolve().parents[1]/"app/src/main/java/com/zhuanz/flymemotion/hook/SystemUiHooks.java"
s=p.read_text(encoding="utf-8")
assert "private NativeSceneChannel liveScene;" in s,"Migration already applied"
def method(name,body):
    global s
    start=s.index("    private "+name);brace=s.index("{",start);end=brace+1;level=1
    while level:
        if s[end]=="{":level+=1
        elif s[end]=="}":level-=1
        end+=1
    s=s[:start]+body+s[end:]
s=s.replace("private NativeWallpaperComposition wallpaperMotion;","private NativeLockscreenBackdrop wallpaperMotion;").replace("    private NativeSceneChannel liveScene;\n","")
start=s.index("            wallpaperMotion = new NativeWallpaperComposition")
end=s.index("            Class<?> rootType",start)
s=s[:start]+'''            wallpaperMotion = new NativeLockscreenBackdrop(loader);
            reporter.liveScene = "native-handoff; no window preparation or wallpaper token lease";
            reporter.sceneReadiness = "native owns returned-window drawing";
'''+s[end:]
start=s.index("    private final Runnable warmWatch")
end=s.index("    private final ViewTreeObserver.OnPreDrawListener",start)
s=s[:start]+s[end:]
s=re.sub(r"^\s*main\.post(?:Delayed)?\(warmWatch(?:,\s*\d+)?\);\s*$","",s,flags=re.M)
s=s.replace("boolean prepared = warming && liveScene.nativeReady() && reporter.wallpaperBound;","boolean prepared = false;")
s=s.replace("if(nativeDismissalRequested){liveScene.cancelNativeExit();nativeDismissalRequested=false;}","nativeDismissalRequested=false;")
start=s.index("        liveScene.begin(gestureLayers[0].getContext(),");end=s.index("        captureLayers();",start);s=s[:start]+s[end:]
s=s.replace('        if (liveScene != null && liveScene.incompatibleFramework()) return blocked("框架适配器未更新，需要重启手机");\n',"")
method("void prepareScene()",'''    private void prepareScene() {
        // Native owns visibility, lifecycle and wallpaper selection. No pre-unlock IPC.
        warming=false;
    }''')
method("void applyWarmPose()","    private void applyWarmPose() { warming=false; }")
method("void applyPose()",'''    private void applyPose() {
        if(failed||!engaged)return;
        float full=((Number)NativeReflection.call(panel,"getMaxPanelTransitionDistance")).floatValue();
        View window=gestureLayers[0];
        MotionFrame frame=MotionFrame.sample(rawHeight,frameVelocity,full,window.getHeight(),canDismiss(),session.config().blurStrength);
        gestureMin=Math.min(gestureMin,frame.progress());gestureMax=Math.max(gestureMax,frame.progress());
        gestureApplied|=pose.apply(frame.contentY(),gestureLayers);
        // Keep the native wallpaper/depth stationary and opaque. No readiness wait,
        // wallpaper mirror, replacement source, or returned-window visibility lease.
        wallpaperPose.apply(frame.withWallpaperProgress(1),pose,wallpaperRoles());
        wallpaperMotion.apply(window,frame);
        renderedRawHeight=rawHeight;lastWallpaperProgress=1;reporter.wallpaperBound=false;
        gestureReason="内容跟手；原生壁纸保留，释放后原生退场";
        reporter.wallpaper="native-engine; no mirror; no retired source";
        reporter.motionFrame="driver=native-handoff; y="+Math.round(frame.contentY())+"; p="+frame.progress()+"; finger="+session.touching();
        reporter.sceneSession=-1;reporter.publishFrame();
        if(Math.abs(frame.progress()-lastProgress)>.00001f){reporter.motion.incrementAndGet();lastProgress=frame.progress();}
    }''')
method("void finish(long generation)",'''    private void finish(long generation) {
        if(generation!=session.generation()||session.touching()||running())return;
        dismissal.presented(generation);
        boolean authenticate=pendingAuthentication||dismissal.consume(generation,progress());
        boolean unlock=authenticate&&canDismiss();pendingAuthentication=waitingForScene=false;
        engaged=false;warming=false;stopRenderTick();session.finish(generation);input.reset();
        // Release visual ownership before native auth/exit listeners. Native then owns
        // the sole wallpaper switch and launcher animation, with no late source restore.
        restoreNativeExpansion();restorePose();wallpaperMotion.release(unlock);
        completeNativeFling(generation,recoveryFlingEnd==generation);
        pendingFinish=finishCommit=presentedFinish=-1;
        if(authenticate)handOffAuthentication(generation);
        trace("finish:native-owner token="+generation+" authenticate="+authenticate);reporter.publish();
    }''')
method("void handOffAuthentication(long generation)",'''    private void handOffAuthentication(long generation) {
        Object state=NativeReflection.get(panel,"mKeyguardStateController");
        if(!failed&&NativeReflection.bool(state,"mShowing")&&!NativeReflection.bool(state,"mKeyguardGoingAway")
                &&!NativeReflection.bool(state,"mKeyguardFadingAway")&&NativeReflection.bool(keyguard,"interactive")
                &&!NativeReflection.bool(keyguard,"keyguardOccluded")&&!NativeReflection.bool(keyguard,"keyguardBouncerShowing")){
            NativeReflection.invoke(showBouncer,NativeReflection.get(panel,"mStatusBarKeyguardViewManager"),"FlymeMotion native handoff");
        }
    }''')
method("void applyExitPose()","    private void applyExitPose(){abandonPose();}")
start=s.index("        try {\n            Runnable cleanup = wallpaperMotion == null");end=s.index("    private record Fling",start)
s=s[:start]+'''        if(wallpaperMotion!=null)wallpaperMotion.release(false);
        reporter.sceneSession=-1;
    }
    private void failWallpaper(Throwable t) {
        wallpaperFailed=true;reporter.wallpaperBound=false;
        if(wallpaperMotion!=null)wallpaperMotion.release(false);
        reporter.wallpaper="原生壁纸；区域模糊停用："+t.getMessage();reporter.publish();
    }
    private void fail(Throwable t){failed=true;dismissal.reset();releaseVisuals();reporter.fail(t);}
'''+s[end:]
assert "liveScene." not in s
p.write_text(s,encoding="utf-8")
print("Migrated: touch/release retained, scene preparation and mirror handoff removed")
