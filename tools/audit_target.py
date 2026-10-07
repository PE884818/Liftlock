"""Read DEX definitions, not decompiler guesses; never writes to the connected device."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def uleb(data, offset):
    value = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7


def dex_definitions(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a standard DEX file")
    uint = lambda offset: struct.unpack_from("<I", data, offset)[0]
    nstrings, strings_offset = uint(56), uint(60)
    strings = []
    for index in range(nstrings):
        _, start = uleb(data, uint(strings_offset + index * 4))
        end = data.index(b"\0", start)
        strings.append(data[start:end].decode("utf-8", errors="replace"))
    types = [strings[uint(uint(68) + i * 4)] for i in range(uint(64))]
    prototypes = []
    for i in range(uint(72)):
        _, returns, params = struct.unpack_from("<III", data, uint(76) + i * 12)
        args = [types[struct.unpack_from("<H", data, params + 4 + n * 2)[0]]
                for n in range(uint(params))] if params else []
        prototypes.append("(" + "".join(args) + ")" + types[returns])
    fields = []
    for i in range(uint(80)):
        owner, kind, name = struct.unpack_from("<HHI", data, uint(84) + i * 8)
        fields.append((types[owner], strings[name], types[kind]))
    methods = []
    for i in range(uint(88)):
        owner, proto, name = struct.unpack_from("<HHI", data, uint(92) + i * 8)
        methods.append((types[owner], strings[name], prototypes[proto]))
    definitions = {}
    for i in range(uint(96)):
        cls, _, parent, _, _, _, offset, _ = struct.unpack_from("<8I", data, uint(100) + i * 32)
        item = {"parent": types[parent] if parent != 0xffffffff else None, "fields": {}, "methods": set()}
        definitions[types[cls]] = item
        if not offset:
            continue
        sizes = []
        for _ in range(4):
            size, offset = uleb(data, offset)
            sizes.append(size)
        for size in sizes[:2]:
            index = 0
            for _ in range(size):
                delta, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                index += delta
                _, name, kind = fields[index]
                item["fields"][name] = kind
        for size in sizes[2:]:
            index = 0
            for _ in range(size):
                delta, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                index += delta
                _, name, signature = methods[index]
                item["methods"].add((name, signature))
    return definitions, set(methods)


def load_apk(path):
    definitions, references = {}, set()
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes, refs = dex_definitions(apk.read(name))
                definitions.update(classes)
                references.update(refs)
    return definitions, references


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--systemui", type=Path, required=True)
    parser.add_argument("--settings", type=Path, required=True)
    parser.add_argument("--shell", type=Path, required=True)
    parser.add_argument("--framework", type=Path, required=True)
    parser.add_argument("--services", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--wallpaper", type=Path, required=True)
    parser.add_argument("--module", type=Path, required=True)
    args = parser.parse_args()
    ui, references = load_apk(args.systemui)
    shell, shell_references = load_apk(args.shell)
    ui.update(shell)
    references.update(shell_references)
    settings, _ = load_apk(args.settings)
    framework, _ = load_apk(args.framework)
    services, _ = load_apk(args.services)
    wallpaper, _ = load_apk(args.wallpaper)
    wallpaper.update(framework)
    checks = []

    def check(name, success):
        checks.append({"name": name, "passed": bool(success)})

    def method(classes, owner, name, signature):
        cls = owner
        found = False
        while cls in classes:
            definition = classes[cls]
            if (name, signature) in definition["methods"]:
                found = True
                break
            cls = definition["parent"]
        check(owner + "->" + name + signature, found)

    def field(classes, owner, name):
        cls = owner
        while cls in classes:
            if name in classes[cls]["fields"]:
                check(owner + "#" + name, True)
                return
            cls = classes[cls]["parent"]
        check(owner + "#" + name, False)

    panel = "Lcom/android/systemui/shade/NotificationPanelViewController;"
    keyguard = "Lcom/android/keyguard/panel/KeyguardPanelViewController;"
    touch = panel[:-1] + "$TouchHandler;"
    method(ui, touch, "onTouchEvent", "(Landroid/view/MotionEvent;)Z")
    method(ui, "Lcom/android/systemui/shade/NotificationShadeWindowView;", "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z")
    method(ui, "Lcom/android/systemui/statusbar/phone/StatusBarKeyguardViewManager;", "showBouncer", "(Ljava/lang/String;)V")
    method(ui, keyguard, "setWallpaperZoom", "(F)V")
    method(ui, keyguard, "calculateWallpaperMatrixArray", "(FFF)[F")
    method(ui, keyguard, "initDeductedImageScale", "()V")
    method(ui, keyguard, "updateKeyguardElementsVisibility", "()V")
    field(ui, keyguard, "statusBarState")
    method(ui, keyguard, "doDeductedImageScaleAnim", "(FLandroid/view/SyncRtSurfaceTransactionApplier;Landroid/view/SurfaceControl;Landroid/view/WallpaperAnimationTarget;)V")
    method(ui, keyguard, "getMatrix$1$1", "()Landroid/graphics/Matrix;")
    method(ui, keyguard[:-1] + "$doWallpaperScaleAnim$1;", "onUpdate", "(Ljava/lang/Object;Ljava/util/Collection;)V")
    method(ui, keyguard[:-1] + "$doWallpaperScaleAnim$1;", "onComplete", "(Ljava/lang/Object;)V")
    method(ui, keyguard[:-1] + "$keyguardUpdateMonitorInjectorCallback$1;", "onKeyguardShowingChanged", "(Z)V")
    method(ui, keyguard[:-1] + "$keyguardUpdateMonitorCallback$1;", "onKeyguardBouncerStateChanged", "(Z)V")
    method(ui, "Lcom/miui/systemui/util/CustomValueAnim;", "clearViewRootImplAddSurfaceControl", "(Landroid/view/View;)V")
    method(ui, "Lcom/miui/miwallpaper/MiuiWallpaperManager;", "getMiuiWallpaperPreview", "(I)Landroid/graphics/Bitmap;")
    method(framework, "Landroid/view/View;", "getViewRootImpl", "()Landroid/view/ViewRootImpl;")
    method(framework, "Landroid/view/WindowManagerGlobal;", "getWallpaperByShowWhenLocked", "(IZ)Landroid/view/WallpaperAnimationTarget;")
    method(framework, "Landroid/view/WallpaperAnimationTarget;", "getLeash", "()Landroid/view/SurfaceControl;")
    method(framework, "Landroid/view/ViewRootImpl;", "setKeyguardMatrixAndAlpha", "(Landroid/view/SurfaceControl;[F)V")
    method(framework, "Landroid/view/ViewRootImpl;", "applyTransactionOnDraw", "(Landroid/view/SurfaceControl$Transaction;)Z")
    method(framework, "Landroid/view/ViewRootImpl;", "getParentSurfaceControl", "()Landroid/view/SurfaceControl;")
    method(framework, "Landroid/view/SurfaceControl;", "isSameSurface", "(Landroid/view/SurfaceControl;)Z")
    method(framework, "Landroid/view/SurfaceControl$Builder;", "setBLASTLayer", "()Landroid/view/SurfaceControl$Builder;")
    method(framework, "Landroid/view/SurfaceControl$Transaction;", "setRelativeLayer", "(Landroid/view/SurfaceControl;Landroid/view/SurfaceControl;I)Landroid/view/SurfaceControl$Transaction;")
    for name in ["mKeyguardSurfaceControl", "mKeyguardMatrix", "mSurfaceControl"]:
        check("Landroid/view/ViewRootImpl;#" + name, name in framework["Landroid/view/ViewRootImpl;"]["fields"])
    check("MiuiWallpaperManager#sInstance", "sInstance" in ui["Lcom/miui/miwallpaper/MiuiWallpaperManager;"]["fields"])
    method(ui, touch, "onInterceptTouchEvent", "(Landroid/view/MotionEvent;)Z")
    method(ui, touch, "handleTouch$1", "(Landroid/view/MotionEvent;)Z")
    method(ui, touch, "onTouch", "(Landroid/view/View;Landroid/view/MotionEvent;)Z")
    method(ui, panel, "handleShadeTouch", "(Landroid/view/MotionEvent;)Z")
    method(ui, "Lcom/android/systemui/shade/NotificationPanelViewControllerInjector;", "handleExternalTouch", "(Landroid/view/MotionEvent;)Z")
    method(ui, "Lcom/android/systemui/shade/NotificationPanelViewControllerInjector;", "setExpandedHeightInternal$1", "(F)V")
    method(ui, "Lcom/android/keyguard/injector/KeyguardPanelViewInjector;", "getKeyguardPanelViewController", "()" + keyguard)
    method(ui, "Lcom/android/keyguard/injector/KeyguardPanelViewInjector;", "isOnKeyguard", "()Z")
    method(ui, panel, "setExpandedHeightInternalAosp", "(F)V")
    method(ui, panel, "flingToHeight", "(FZFFZ)V")
    method(ui, panel, "fling$1", "(FFZZ)V")
    method(ui, panel, "onTrackingStopped$1", "(Z)V")
    method(ui, keyguard, "startUnlockKeyguardDismissAnim", "(Lcom/android/keyguard/injector/KeyguardViewMediatorInjector$startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation$1;)V")
    method(ui, "Lcom/android/systemui/plugins/FalsingManager;", "isUnlockingDisabled", "()Z")
    method(ui, "Lkotlinx/coroutines/flow/StateFlowImpl;", "setValue", "(Ljava/lang/Object;)V")
    method(ui, panel, "isFalseTouch", "(IFF)Z")
    method(ui, panel, "getMaxPanelTransitionDistance", "()I")
    method(ui, panel, "isTracking", "()Z")
    method(ui, panel, "setDozing$1", "(Z)V")
    method(ui, keyguard, "updateKeyguardElementsExpansionInternal", "(F)V")
    method(ui, keyguard, "updateKeyguardElementsExpansion", "(Z)V")
    method(ui, keyguard, "cancelWakeupAnimations", "()V")
    method(ui, "Lcom/android/keyguard/panel/KeyguardPanelViewController$wakeObserver$1;", "onStartedWakingUp", "()V")
    method(ui, "Lcom/android/keyguard/panel/KeyguardPanelViewController$wakeObserver$1;", "onStartedGoingToSleep", "()V")
    method(ui, "Lmiuix/animation/Folme;", "useValue", "([Ljava/lang/Object;)Lmiuix/animation/IStateStyle;")
    method(ui, "Lmiuix/animation/ICancelableStyle;", "cancel", "()V")
    method(ui, panel, "-$$Nest$minitDownStates", "(" + panel + "Landroid/view/MotionEvent;)V")
    method(framework, "Landroid/view/ViewGroup;", "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z")
    field(ui, panel, "mTouchHandler")
    field(ui, keyguard, "translationAlphaValue")
    method(ui, panel, "-$$Nest$mendMotionEvent", "(" + panel + "Landroid/view/MotionEvent;FFZ)V")
    method(ui, keyguard, "setBouncerShowingFraction$default", "(" + keyguard + "FZI)V")
    method(ui, keyguard, "doBlurAndDim", "(FZZZ)V")
    method(ui, "Lcom/android/keyguard/panel/KeyguardMoveHelper;", "isInCenterScreen", "()Z")
    method(ui, "Lcom/android/keyguard/panel/KeyguardMoveHelper;", "isInMagazineAnimation", "()Z")
    method(ui, "Lcom/android/systemui/shade/QuickSettingsControllerImpl;", "getExpanded", "()Z")
    method(ui, "Lcom/miui/systemui/controlcenter/ControlCenterImpl;", "getExpanded", "()Z")
    for owner, names in {
        panel: "mView mBarState mDozing mExpandedHeight mExpandedFraction mHeightAnimator mVelocityTracker mQsController mKeyguardStateController mFlingAnimationUtils mKeyguardPanelViewInjector mExpandingFromHeadsUp mFalsingManager mInitialTouchFromKeyguard mInitialExpandX mInitialExpandY mTouchSlop mDownX mStatusBarKeyguardViewManager mTrackingPointer mStatusBarLongPressDowntime mAmbientState".split(),
        touch: ["this$0"],
        keyguard: "interactive keyguardShowing keyguardBouncerShowing keyguardOccluded wakeupAnimState viewAlphaAnimator hideKeyguardViewFolmeRunning displayStateShaderController editorState keyguardRootView animationViews notificationShadeWindowView notificationPanelView keyguardClockInjector notificationContainerParent keyguardBottomAreaInjector keyguardPanelViewInjector controlCenter".split(),
        "Lcom/android/keyguard/screenfade/DisplayStateShaderController;": ["isScreenFadeAnimationRunning"],
        "Lcom/android/keyguard/injector/KeyguardClockInjector;": ["keyguardClockView"],
        "Lcom/android/keyguard/injector/KeyguardBottomAreaInjector;": ["mKeyguardBottomAreaView"],
        "Lcom/android/keyguard/panel/KeyguardMoveHelper;": "mSwipingInProgress mIsShortcutMoving".split(),
        "Lcom/android/keyguard/injector/KeyguardPanelViewInjector;": ["keyguardMoveHelper"],
        "Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;": "mCanDismissLockScreen mShowing mKeyguardGoingAway mKeyguardFadingAway mMiKeyguardFadingAway".split(),
        "Lcom/android/systemui/statusbar/notification/stack/AmbientState;": "mIsSwipingUp mIsFlingRequiredAfterLockScreenSwipeUp".split(),
        "Lcom/android/systemui/statusbar/phone/StatusBarKeyguardViewManager;": ["mPrimaryBouncerInteractor"],
        "Lcom/android/systemui/bouncer/domain/interactor/PrimaryBouncerInteractor;": ["repository"],
        "Lcom/android/systemui/bouncer/data/repository/KeyguardBouncerRepositoryImpl;": ["_keyguardPosition"],
    }.items():
        for name in names:
            check(owner + "->" + name, name in ui.get(owner, {}).get("fields", {}))
    for owner in ["Lcom/android/keyguard/blur/MiuiKeyguardBlurInteractor;",
                  "Lcom/miui/systemui/shade/blur/ShadeWindowBlurControllerImpl;"]:
        check(owner, owner in ui)
    external = ("Lcom/android/wm/shell/animation/FlingAnimationUtils;", "getMinVelocityPxPerSecond", "()F")
    check("Native FlingAnimationUtils method reference (runtime resolution still required)", external in references)
    method(ui, external[0], external[1], external[2])
    method(ui, "Lcom/android/keyguard/injector/KeyguardClockInjector;", "getSecondaryClockLayerView", "()Landroid/view/View;")
    method(ui, "Lkotlinx/coroutines/flow/ReadonlyStateFlow;", "getValue", "()Ljava/lang/Object;")
    method(framework, "Landroid/view/WindowManagerGlobal;", "getInstance", "()Landroid/view/WindowManagerGlobal;")
    method(framework, "Landroid/view/WindowManagerGlobal;", "getWallpaperByShowWhenLocked", "(IZ)Landroid/view/WallpaperAnimationTarget;")
    method(framework, "Landroid/view/ViewRootImpl;", "setKeyguardMatrixAndAlpha", "(Landroid/view/SurfaceControl;[F)V")
    method(framework, "Landroid/view/ViewRootImpl;", "applyTransactionOnDraw", "(Landroid/view/SurfaceControl$Transaction;)Z")
    method(framework, "Landroid/view/SurfaceControl;", "mirrorSurface", "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl;")
    method(framework, "Landroid/view/SurfaceControl;", "<init>", "(Landroid/view/SurfaceControl;Ljava/lang/String;)V")
    method(services, "Lcom/android/server/wm/WallpaperWindowToken;", "isVisible", "()Z")
    method(services, "Lcom/android/server/wm/WallpaperWindowToken;", "isVisibleRequested", "()Z")
    check("Landroid/view/WallpaperAnimationTarget;->leash", "leash" in framework.get("Landroid/view/WallpaperAnimationTarget;", {}).get("fields", {}))
    method(settings, "Lcom/android/settings/AodAndLockScreenSettings;", "onResume", "()V")
    method(settings, "Lmiuix/preference/TextPreference;", "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;)V")
    method(settings, "Landroidx/preference/PreferenceGroup;", "findPreference", "(Ljava/lang/CharSequence;)Landroidx/preference/Preference;")
    method(settings, "Landroidx/preference/PreferenceGroup;", "addPreference", "(Landroidx/preference/Preference;)Z")
    wm = "Lcom/android/server/wm/"
    for owner, name, sig in [
        ("KeyguardController", "setWindowManager", "(" + wm + "WindowManagerService;)V"),
        ("KeyguardController", "checkKeyguardVisibility", "(" + wm + "ActivityRecord;)Z"),
        ("KeyguardController", "canDismissKeyguard", "()Z"),
        ("KeyguardController", "setKeyguardShown", "(IZZ)V"),
        ("WindowManagerService", "systemReady", "()V"),
        ("WindowManagerService", "requestTraversal", "()V"),
        ("WallpaperWindowToken", "updateWallpaperWindows", "(Z)V"),
        ("WallpaperWindowToken", "updateSurfaceVisibility", "(Landroid/view/SurfaceControl$Transaction;)V"),
        ("WallpaperWindowToken", "setVisibility", "(Z)V"),
        ("WallpaperWindowToken", "canShowWhenLocked", "()Z"),
        ("RootWindowContainer", "ensureActivitiesVisible", "()V"),
        ("RootWindowContainer", "getDefaultDisplay", "()" + wm + "DisplayContent;"),
        ("WallpaperController", "adjustWallpaperWindows", "()V"),
        ("WallpaperController", "getWallpaperSurface", "(Z)Landroid/view/WallpaperAnimationTarget;"),
        ("WindowToken", "setClientVisible", "(Z)V"),
        ("WindowToken", "isClientVisible", "()Z"),
        ("DisplayContent", "getDefaultTaskDisplayArea", "()" + wm + "TaskDisplayArea;"),
        ("DisplayContent", "isSleeping", "()Z"),
        ("EnsureActivitiesVisibleHelper", "setActivityVisibilityState", "(" + wm + "ActivityRecord;" + wm + "ActivityRecord;Z)V"),
        ("TaskDisplayArea", "getFocusedRootTask", "()" + wm + "Task;"),
        ("Task", "topRunningActivityLocked", "()" + wm + "ActivityRecord;"),
        ("ActivityRecord", "attachedToProcess", "()Z"),
        ("ActivityRecord", "shouldBeVisibleUnchecked", "()Z"),
        ("ActivityRecord", "setVisibility", "(Z)V"),
        ("ActivityRecord", "setVisibilityInner", "(Z)V"),
        ("ActivityRecord", "commitVisibility", "(ZZ)V"),
        ("ActivityRecord", "commitVisibility", "(ZZZ)V"),
        ("ActivityRecord", "getDisplayId", "()I"),
        ("ActivityTaskManagerService", "getCurrentUserId", "()I"),
        ("ActivityRecord", "getUid", "()I"),
        ("ActivityRecord", "activityResumedLocked", "(Landroid/os/IBinder;Z)V"),
        ("ActivityRecord", "getTaskFragment", "()" + wm + "TaskFragment;"),
        ("TaskFragment", "startPausing", "(ZZ" + wm + "ActivityRecord;Ljava/lang/String;)Z"),
        ("TaskFragment", "shouldSleepActivities", "()Z"),
        ("WindowState", "isSecureLocked", "()Z"),
        ("WindowState", "isDrawn", "()Z"),
        ("WindowState", "getSurfaceControl", "()Landroid/view/SurfaceControl;"),
        ("WindowState", "assignLayer", "(Landroid/view/SurfaceControl$Transaction;I)V"),
        ("WindowStateAnimator", "getShown", "()Z"),
        ("WindowContainer", "assignRelativeLayer", "(Landroid/view/SurfaceControl$Transaction;Landroid/view/SurfaceControl;IZ)V"),
    ]:
        method(services, wm + owner + ";", name, sig)
    for name in ["isKeyguardShowing", "isKeyguardLocked", "isAodShowing", "isKeyguardOccluded", "isKeyguardGoingAway"]:
        method(services, wm + "KeyguardController;", name, "(I)Z")
    for name, signature in [("startedGoingToSleepGlobal", "(I)V"), ("startedWakingUpGlobal", "(I)V"),
                            ("startedGoingToSleep", "(IIZ)V"), ("startedWakingUp", "(IIZ)V")]:
        method(services, "Lcom/android/server/policy/PhoneWindowManager;", name, signature)
    for owner in ["RootWindowContainer", "ActivityRecord"]:
        method(services, wm + owner + ";", "getWindow", "(Ljava/util/function/Predicate;)" + wm + "WindowState;")
    for owner, names in {
        "KeyguardController": "mService mRootWindowContainer",
        "WindowManagerService": "mContext mH mGlobalLock",
        "DisplayContent": "mWallpaperController mCurrentFocus",
        "TaskFragment": "mResumedActivity",
        "WallpaperController": "mWallpaperTokens",
        "WallpaperWindowToken": "mChildren",
        "ActivityRecord": "finishing mUserId mDisplayContent token",
        "WindowState": "mAttrs mOwnerUid mLastLayer mLastRelativeToLayer mWinAnimator mLastHScale mLastVScale mLastSurfacePosition mViewVisibility mClient",
    }.items():
        for name in names.split():
            field(services, wm + owner + ";", name)
    method(framework, "Landroid/app/ActivityThread;", "currentActivityThread", "()Landroid/app/ActivityThread;")
    method(framework, "Landroid/app/ActivityThread;", "getSystemContext", "()Landroid/app/ContextImpl;")
    method(framework, "Landroid/view/WindowManager;", "useClientSurface", "()Z")
    # New scene adapter: exact OEM boolean method semantics are pinned in the analysis note.
    for owner, name, signature in [
        (wm+"ActivityRecord;", "shouldResumeActivity", "(Lcom/android/server/wm/ActivityRecord;)Z"),
        (wm+"ActivityRecord;", "makeActiveIfNeeded", "(Lcom/android/server/wm/ActivityRecord;)Z"),
        (wm+"ActivityRecord;", "shouldStartActivity", "()Z"),
        (wm+"WallpaperWindowToken;", "getParentSurfaceControl", "()Landroid/view/SurfaceControl;")]:
        method(services, owner, name, signature)
    method(framework, "Landroid/view/ViewRootImpl;", "getSurfaceControl", "()Landroid/view/SurfaceControl;")
    method(framework, "Landroid/view/SurfaceControl$Builder;", "setContainerLayer", "()Landroid/view/SurfaceControl$Builder;")
    method(framework, "Landroid/view/ViewRootImpl;", "createBackgroundBlurDrawable", "()Lcom/android/internal/graphics/drawable/BackgroundBlurDrawable;")
    for name, signature in [
        ("setBlurRadius", "(I)V"),
        ("setCropRect", "(ZLandroid/graphics/Rect;)Z"),
        ("setBlendModes", "([[I)Z"),
        ("invalidateBlurRegion", "()V")]:
        method(framework, "Lcom/android/internal/graphics/drawable/BackgroundBlurDrawable;", name, signature)
    for owner, name, signature in [
        ("Landroid/view/SurfaceControl;", "getLayerId", "()I"),
        ("Landroid/view/SurfaceControl;", "copyFrom", "(Landroid/view/SurfaceControl;Ljava/lang/String;)V"),
        ("Landroid/view/SurfaceControl$Transaction;", "setVisibility", "(Landroid/view/SurfaceControl;Z)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "show", "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "hide", "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "apply", "(ZZ)V"),
        ("Landroid/view/SurfaceControl$Transaction;", "apply", "()V"),
        ("Landroid/view/SurfaceControl$Transaction;", "apply", "(Z)V"),
        ("Landroid/view/SurfaceControl$Transaction;", "applyAsyncUnsafe", "()V"),
        ("Landroid/view/SurfaceControl$Transaction;", "clear", "()V"),
        ("Landroid/view/SurfaceControl$Transaction;", "close", "()V"),
        ("Landroid/view/SurfaceControl$Transaction;", "merge", "(Landroid/view/SurfaceControl$Transaction;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "reparent", "(Landroid/view/SurfaceControl;Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "addTransactionCommittedListener", "(Ljava/util/concurrent/Executor;Landroid/view/SurfaceControl$TransactionCommittedListener;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Builder;", "build", "()Landroid/view/SurfaceControl;"),
        ("Landroid/view/SurfaceControl$Builder;", "setEffectLayer", "()Landroid/view/SurfaceControl$Builder;"),
        ("Landroid/view/SurfaceControl$Transaction;", "setCrop", "(Landroid/view/SurfaceControl;Landroid/graphics/Rect;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "setBackgroundBlurRadius", "(Landroid/view/SurfaceControl;I)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "setBackgroundBlurScale", "(Landroid/view/SurfaceControl;F)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$Transaction;", "setBlurRegions", "(Landroid/view/SurfaceControl;[[FLjava/util/List;Ljava/util/List;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/ViewRootImpl;", "getReqSfScale", "()V"),
        ("Landroid/view/ViewRootImpl;", "checkSurTexSize", "()V")]:
        method(framework, owner, name, signature)
    for owner, names in [("Landroid/view/SurfaceControl$Builder;", ["mFlags", "mParent"]),
                         ("Landroid/view/ViewRootImpl;", ["mTexScale", "mDrawSfScale"])]:
        for name in names: field(framework, owner, name)
    for owner, name, signature in [
        (wm+"WindowContainer;", "getParent", "()Lcom/android/server/wm/WindowContainer;"),
        (wm+"WindowState;", "isVisibleRequested", "()Z"),
        (wm+"ActivityRecord;", "isActivityTypeHome", "()Z")]: method(services, owner, name, signature)
    for owner, name in [("WindowStateAnimator", "mSurfaceControl")]:
        field(services, wm+owner+";", name)
    method(ui, keyguard, "onWakeupAnimationEnd", "()V")
    for name in ["keyguardForegroundLayer", "keyguardBackgroundLayer", "keyguardDepthInteractor"]: field(ui, keyguard, name)
    field(ui, "Lcom/android/keyguard/depth/KeyguardDepthInteractor;", "deductedImageView")
    for owner, name in [("Lcom/miui/systemui/shade/blur/ShadeWindowBlurControllerImpl;", "windowBlurUtils"),
                        ("Lcom/miui/systemui/shade/blur/ShadeWindowBlurControllerImpl;", "dimAlpha"),
                        ("Lcom/miui/systemui/shade/blur/ShadeWindowBlurControllerImpl;", "dimRGB"),
                        ("Lcom/miui/systemui/util/WindowBlurUtilsImpl;", "windowBlurAvailableState"),
                        ("Lcom/miui/systemui/util/WindowBlurUtilsImpl;", "windowBlurAvailable"),
                        ("Lcom/miui/systemui/util/WindowBlurUtilsImpl;", "blurUtils")]: field(ui, owner, name)
    method(ui, "Lcom/android/systemui/statusbar/BlurUtils;", "blurRadiusOfRatio", "(F)F")
    method(ui, "Lcom/android/systemui/statusbar/BlurUtils;", "supportsBlursOnWindows", "()Z")
    method(ui, panel, "onFlingEnd", "(Z)V")
    method(ui, "Lcom/android/systemui/statusbar/BlurUtils;", "applyBlur", "(Landroid/view/ViewRootImpl;I)V")
    for name in ["lastTargetViewRootImpl", "lastAppliedBlur"]: field(ui, "Lcom/android/systemui/statusbar/BlurUtils;", name)
    method(framework, "Landroid/view/ViewRootImpl;", "setWallpaperBlur", "(I)V")
    field(framework, "Landroid/view/ViewRootImpl;", "mDeductedblur")
    for listener in ["$6", "$8"]:
        method(ui, "Lcom/android/systemui/shade/NotificationPanelViewController" + listener + ";", "onAnimationEnd", "(Landroid/animation/Animator;)V")
    method(ui, "Lcom/miui/systemui/shade/blur/ShadeWindowBlurControllerImpl$updateBlurRatioRunnable$1;", "run", "()V")
    method(ui, keyguard, "setWallpaperSyncBlur", "(FLandroid/view/View;)V")
    method(framework, "Landroid/service/wallpaper/WallpaperService$Engine;", "isPreview", "()Z")
    field(framework, "Landroid/service/wallpaper/WallpaperService$Engine;", "mWindow")
    base = "Lcom/miui/miwallpaper/wallpaperservice/impl/ImageEngineImpl;"
    universal = "Lcom/miui/miwallpaper/wallpaperservice/UniversalWallpaper$UniversalEngine;"
    for name, signature in [("U", "(Z)V"), ("M", "(Z)V")]: method(wallpaper, base, name, signature)
    for concrete in ["desktop/DesktopImageEngineImpl", "keyguard/KeyguardImageEngineImpl"]:
        method(wallpaper, "Lcom/miui/miwallpaper/wallpaperservice/impl/" + concrete + ";", "s", "()I")
    for name in ["j", "g"]: field(wallpaper, base, name)
    stream="Lcom/miui/miwallpaper/wallpaperservice/impl/keyguard/KeyguardStreamImageEngineImpl;"
    check("lock stream directly inherits supported image renderer",wallpaper.get(stream,{}).get("parent")==base)
    method(wallpaper,stream,"showKeyguardWallpaper","(ZI)V")
    method(wallpaper,stream,"hideKeyguardWallpaper","()V")
    check("lock stream has no s() role getter",("s","()I") not in wallpaper.get(stream,{}).get("methods",set()) and ("s","()I") not in wallpaper[base]["methods"])
    for name in ["onSurfaceCreated", "onSurfaceDestroyed", "onSurfaceRedrawNeeded"]:
        method(wallpaper, universal, name, "(Landroid/view/SurfaceHolder;)V")
    method(wallpaper, universal, "onVisibilityChanged", "(Z)V")
    for name in ["f", "mSurfaceControl", "mBbqSurfaceControl"]: field(wallpaper, universal, name)
    method(wallpaper, "Lt0/a;", "l", "()Z")
    expand = "Lcom/android/systemui/shade/NotificationPanelExpandController;"
    anim = "Lcom/android/systemui/shade/MiuiNotificationPanelAnimControllerImpl;"
    for name, signature in [("setExpandHeight", "(F)V"), ("onExpandFinish", "()V"),
                            ("show", "(Z)V"), ("hide", "(Z)V"), ("setAppearance", "(Z)V"),
                            ("notifyAppearanceChanged", "(ZZ)V"), ("access$updatePanelVisible", "("+expand+")V")]:
        method(ui, expand, name, signature)
    for name in ("panelViewController", "shadeTouchHandler", "expandHeight", "expandThresh", "trackingInternal",
                 "panelAnimator", "headerExpansionAnimator", "headerStretchAnimator", "blurAnimator", "appearance"):
        field(ui, expand, name)
    method(ui, anim, "startPanelExpandAnimation$default", "("+anim+"ZFZI)V")
    method(ui, anim, "startPanelVisibleAnimation", "(ZZZ)V")
    suffix = "$packages__apps__MiuiSystemUI__packages__SystemUI__android_common__MiuiSystemUI_core"
    for owner, name, signature in [("Expansion", "updateExpansion", "(FZ)V"),
                                    ("Blur", "updateBlurRatio", "(FZ)V"), ("Stretch", "updateStretch", "(FFZZ)V")]:
        method(ui, "Lcom/android/systemui/shade/NotificationPanel"+owner+"Animator;", name+suffix, signature)
    method(ui, "Lcom/android/systemui/shade/NotificationShadeWindowView;", "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z")
    injector = "Lcom/android/systemui/shade/NotificationPanelViewControllerInjector;"
    method(ui, injector, "handleExternalTouch", "(Landroid/view/MotionEvent;)Z")
    for name in ("mBarState", "mDozing", "mNotifInjector", "mKeyguardStateController", "mView"):
        field(ui, panel, name)
    method(ui, panel, "setDozing$1", "(Z)V")
    for name in ("shadeSwitchController", "expandingFromHeadsUp", "nssCoveringQs"):
        field(ui, injector, name)
    for name in ("mShowing", "mKeyguardGoingAway"):
        field(ui, "Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;", name)
    # Resolve actual runtime class names rather than relying on decompiler package assumptions.
    routers = [owner for owner in ui if owner.endswith("/MiuiShadeTouchHandlerImpl;")]
    check("exactly one native shade touch router", len(routers) == 1)
    if routers: field(ui, routers[0], "dispatchedToCtrl")
    switchers = [owner for owner in ui if owner.endswith("/ShadeSwitchController;")]
    check("exactly one native shade switch controller", len(switchers) == 1)
    if switchers: method(ui, switchers[0], "getSwitching", "()Z")
    for name in ("keyguardShowing", "statusBarState", "keyguardClockInjector", "notificationPanelViewControllerInjector", "keyguardBottomAreaInjector"):
        field(ui, keyguard, name)
    method(ui, keyguard, "onKeyguardViewBind", "(Landroidx/constraintlayout/widget/ConstraintLayout;)V")
    method(ui, keyguard, "updateKeyguardElementsVisibility", "()V")
    field(ui, "Lcom/android/keyguard/injector/KeyguardClockInjector;", "keyguardClockView")
    field(ui, "Lcom/android/keyguard/injector/KeyguardBottomAreaInjector;", "mKeyguardBottomAreaView")
    method(ui, "Lcom/android/keyguard/clock/KeyguardClockContainer;", "getSecondaryClockLayerView", "()Landroid/view/View;")
    method(ui, panel, "isTracking", "()Z")
    field(ui, injector, "notificationStackScroller")
    method(ui, "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;", "getTopPadding", "()I")
    method(ui, "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;", "updateTopPadding", "(FZ)V")
    method(ui, injector, "getPanelViewController", "()Lcom/android/systemui/shade/NotificationPanelViewController;")
    for name in ("setVisibility", "setAlpha", "setScaleX", "setScaleY", "setTranslationY", "setTransitionAlpha"):
        method(framework, "Landroid/view/View;", name, "(I)V" if name=="setVisibility" else "(F)V")
    method(framework, "Landroid/view/View;", "getTransitionAlpha", "()F")
    method(framework, "Landroid/service/wallpaper/WallpaperService$Engine;", "getSurfaceHolder", "()Landroid/view/SurfaceHolder;")
    method(framework, "Landroid/view/SurfaceControl$Transaction;", "setMatrix", "(Landroid/view/SurfaceControl;FFFF)Landroid/view/SurfaceControl$Transaction;")
    for name in ("addSurfaceChangedCallback", "removeSurfaceChangedCallback"):
        method(framework,"Landroid/view/ViewRootImpl;",name,"(Landroid/view/ViewRootImpl$SurfaceChangedCallback;)V")
    for name,signature in (("surfaceCreated","(Landroid/view/SurfaceControl$Transaction;)V"),("surfaceReplaced","(Landroid/view/SurfaceControl$Transaction;)V"),("surfaceDestroyed","()V")):
        method(framework,"Landroid/view/ViewRootImpl$SurfaceChangedCallback;",name,signature)
    method(framework,"Landroid/view/ViewRootImpl;","setWindowStopped","(Z)V")
    # Withdrawn notification feature must not be packaged or restored by old preferences.
    module, _ = load_apk(args.module)
    for owner in ("hook/NotificationMotionHooks","hook/SharedLockscreenLayout","hook/NotificationWallpaper","hook/NativeNotificationPresentation","ViewPresentationLease","core/NotificationSheet","core/NotificationPresentationPolicy","core/SheetTouchProgress","core/ValuePresentationLease"):
        check("notification integration removed "+owner,"Lcom/zhuanz/flymemotion/"+owner+";" not in module)
    launcher="Lcom/android/systemui/keyguard/KeyguardUnlockAnimationController;"
    proxy="Lcom/android/systemui/shared/system/smartspace/ILauncherUnlockAnimationController$Stub$Proxy;"
    method(ui,launcher,"setLauncherUnlockController","(Ljava/lang/String;"+proxy+")V")
    for name in ("onKeyguardGoingAwayChanged","unlockToLauncherWithInWindowAnimations"):
        method(ui,launcher,name,"()V")
    method(ui,launcher,"isSupportedLauncherUnderneath","()Z")
    method(ui,proxy,"prepareForUnlock","(Landroid/graphics/Rect;ZI)V")
    method(ui,proxy,"playUnlockAnimation","(J)V")
    method(ui,proxy,"setUnlockAmount","(Z)V")
    for owner in ("hook/LateDesktopAnimationHooks","core/DesktopAnimationGate","hook/NativeSceneChannel","hook/NativeWallpaperComposition"):
        check("lockscreen-only runtime helper "+owner,"Lcom/zhuanz/flymemotion/"+owner+";" in module)
    for name in ("addKeyguardLockedStateListener", "removeKeyguardLockedStateListener"):
        method(services, wm+"WindowManagerService;", name, "(Lcom/android/internal/policy/IKeyguardLockedStateListener;)V")
    method(services, wm+"WindowManagerService;", "dispatchKeyguardLockedState", "()V")
    method(services, wm+"WindowManagerService;", "lambda$dispatchKeyguardLockedState$7", "()V")
    method(services, wm+"WindowManagerService;", "isKeyguardLocked", "()Z")
    method(framework, "Lcom/android/internal/policy/IKeyguardLockedStateListener$Stub$Proxy;", "onKeyguardLockedStateChanged", "(Z)V")
    for owner in ("hook/LateHomeLockedStateHooks","core/HomeUnlockGate"):
        check("HOME-only native notification helper "+owner,"Lcom/zhuanz/flymemotion/"+owner+";" in module)
    check("wallpaper role pose in delivered DEX", "Lcom/zhuanz/flymemotion/WallpaperLayerPose;" in module)
    check("native regional blur lease in delivered DEX", "Lcom/zhuanz/flymemotion/hook/NativeRegionalBlurLease;" in module)
    with zipfile.ZipFile(args.module) as apk:
        scopes = apk.read("META-INF/xposed/scope.list").decode().split()
    check("scopes exactly SystemUI, Settings, system, MiWallpaper", set(scopes) == {
        "com.android.systemui", "com.android.settings", "system", "com.miui.miwallpaper"})
    check("no static preview adapter in delivered DEX", "Lcom/zhuanz/flymemotion/hook/NativeHomeBackdrop;" not in module)
    check("no wallpaper translation adapter in delivered DEX", "Lcom/zhuanz/flymemotion/hook/NativeWallpaperMotion;" not in module)
    check("unified motion frame in delivered DEX", "Lcom/zhuanz/flymemotion/core/MotionFrame;" in module)
    method(module, "Lcom/zhuanz/flymemotion/core/RevealGate;", "compose",
           "(Lcom/zhuanz/flymemotion/core/MotionFrame;FZJ)Lcom/zhuanz/flymemotion/core/MotionFrame;")
    method(module, "Lcom/zhuanz/flymemotion/core/WallpaperBaseline;", "snapshot", "(JJ[F)[F")
    for owner, name, signature in [
        ("Landroid/view/SurfaceControl;", "nativeAddTransactionCompletedListener", "(JLjava/util/function/Consumer;)V"),
        ("Landroid/view/SurfaceControl$Transaction;", "remove", "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;"),
        ("Landroid/view/SurfaceControl$TransactionStats;", "getLatchTimeNanos", "()J"),
        ("Landroid/view/SurfaceControl$TransactionStats;", "getPresentFence", "()Landroid/hardware/SyncFence;"),
        ("Landroid/view/SurfaceControl$TransactionStats;", "close", "()V"),
        ("Landroid/hardware/SyncFence;", "await", "(Ljava/time/Duration;)Z"),
        ("Landroid/hardware/SyncFence;", "isValid", "()Z"),
        ("Landroid/hardware/SyncFence;", "close", "()V")]:
        method(framework, owner, name, signature)
    field(framework, "Landroid/view/SurfaceControl$Transaction;", "mNativeObject")
    for name, signature in [("finishDrawing", "(Landroid/view/SurfaceControl$Transaction;I)Z"),
                            ("applyWithNextDraw", "(Ljava/util/function/Consumer;)V"),
                            ("requestRedrawForSync", "()V"), ("forceReportingResized", "()V"), ("reportResized", "()V")]:
        method(services, wm + "WindowState;", name, signature)
    for name in ("mBufferSeqId", "mSyncSeqId"): field(services, wm + "WindowState;", name)
    field(services, wm + "WindowManagerService;", "mAlwaysSeqId")
    method(services, wm + "WindowManagerService;", "finishDrawingWindow", "(" + wm + "Session;Landroid/view/IWindow;Landroid/view/SurfaceControl$Transaction;I)V")
    method(services, wm + "Session;", "finishDrawing", "(Landroid/view/IWindow;Landroid/view/SurfaceControl$Transaction;I)V")
    method(services, wm + "ClientLifecycleManager;", "dispatchPendingTransaction", "(Landroid/app/IApplicationThread;)V")
    field(services, wm + "ClientLifecycleManager;", "mPendingTransactions")
    method(services, wm + "ActivityTaskManagerService;", "getLifecycleManager", "()" + wm + "ClientLifecycleManager;")
    method(services, wm + "WindowState;", "getProcess", "()" + wm + "WindowProcessController;")
    method(services, wm + "WindowProcessController;", "getThread", "()Landroid/app/IApplicationThread;")
    method(services, wm + "KeyguardController;", "getDisplayState", "(I)" + wm + "KeyguardController$KeyguardDisplayState;")
    for name in ("mKeyguardShowing", "mKeyguardGoingAway", "mAodShowing"):
        field(services, wm + "KeyguardController$KeyguardDisplayState;", name)
    for name in ("ScenePresentation", "SceneStatusOrder", "LockCycle", "BufferDrawRequest"):
        check("presentation policy " + name, "Lcom/zhuanz/flymemotion/core/" + name + ";" in module)
    for name in ("ServerSceneCover", "NativePresentationFence", "TargetBufferDrawRequest"):
        check("production helper " + name, "Lcom/zhuanz/flymemotion/hook/" + name + ";" in module)
    method(services, wm + "WindowState;", "getClientViewRootSurface", "()Landroid/view/SurfaceControl;")
    method(services, wm + "WindowState;", "sendAppVisibilityToClients", "()V")
    method(framework, "Landroid/view/ViewRootImpl;", "setReportNextDraw", "(ZLjava/lang/String;)V")
    method(framework, "Landroid/view/WindowManagerGlobal;", "getInstance", "()Landroid/view/WindowManagerGlobal;")
    for name in ("mRoots", "mLock"): field(framework, "Landroid/view/WindowManagerGlobal;", name)
    for name in ("mWindow", "mSurfaceControl", "mView"): field(framework, "Landroid/view/ViewRootImpl;", name)
    for name in ("NativeSurfaceFrame", "ClientFrameHooks"):
        check("no application producer hook " + name, "Lcom/zhuanz/flymemotion/hook/" + name + ";" not in module)
    with zipfile.ZipFile(args.module) as apk:
        check("no client JNI runtime packaged", not any("libflymesurfaceframe.so" in n for n in apk.namelist()))
    method(services, wm+"KeyguardController;", "keyguardGoingAway", "(I)V")
    report = {
        "result": "passed" if all(c["passed"] for c in checks) else "failed",
        "count": len(checks), "checks": checks,
        "systemuiSha256": hashlib.sha256(args.systemui.read_bytes()).hexdigest(),
        "settingsSha256": hashlib.sha256(args.settings.read_bytes()).hexdigest(),
        "shellSha256": hashlib.sha256(args.shell.read_bytes()).hexdigest(),
        "frameworkSha256": hashlib.sha256(args.framework.read_bytes()).hexdigest(),
        "servicesSha256": hashlib.sha256(args.services.read_bytes()).hexdigest(),
        "systemuiClassDefinitions": len(ui), "settingsClassDefinitions": len(settings),
        "runtimeHooksVerified": False,
        "wallpaperSha256": hashlib.sha256(args.wallpaper.read_bytes()).hexdigest(),
        "moduleSha256": hashlib.sha256(args.module.read_bytes()).hexdigest(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("result", "count", "runtimeHooksVerified")}, ensure_ascii=False))
    for failure in checks:
        if not failure["passed"]:
            print("MISSING", failure["name"])
    raise SystemExit(0 if report["result"] == "passed" else 1)


if __name__ == "__main__":
    main()
