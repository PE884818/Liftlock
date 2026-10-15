package com.zhuanz.flymemotion.hook;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.BroadcastOptions;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Looper;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.SceneProtocol;
import java.util.HashMap;
import java.util.Map;

/** Ordered background IPC. Mirror ownership and render callbacks are marshalled to the main thread. */
final class NativeSceneChannel {
    static final String ACTION = SceneProtocol.REQUEST, STATUS = SceneProtocol.STATUS;
    static final String PERMISSION = "android.permission.STATUS_BAR_SERVICE";
    record MirrorState(long session, long generation, SurfaceControl surface) {}
    private final Handler worker, main = new Handler(Looper.getMainLooper());
    private final RuntimeReporter reporter;
    private final Map<Long, Runnable> retiring = new HashMap<>();
    private Context context;
    private volatile long token = -1, expectedToken = -1;
    private volatile boolean lateDesktopAnimation;
    private boolean dismissible, requested, lastRequested, listening, revealing, revealSent, homeDeferred;
    private String target = "NONE";
    private volatile boolean nativeReady;
    private volatile boolean backdropReady;
    private boolean frameworkKnown, frameworkCompatible;
    // A framework adapter that could not install on this build never answers. After the first
    // request has gone unanswered for a while, unlocks are handed to native without a scene.
    private volatile long firstBeginAt = -1;
    private volatile boolean everHeard;
    private boolean revoked;
    private long latestToken;
    // A framework adapter that IS answering but whose scene never moves: its readiness reports a
    // return window it cannot present, so no scene will arrive either. Tracked so that case can
    // take the same route as an adapter that never answered at all.
    private long progressMark = Long.MIN_VALUE, progressAt;
    private static final long SCENE_STALL_MS = 1500;
    // The framework's own readiness report, as last received: it names whether the return window
    // can be presented at all, which is what the handover decision below is based on.
    private String lastReadiness = "";
    private final com.zhuanz.flymemotion.core.SceneStatusOrder order = new com.zhuanz.flymemotion.core.SceneStatusOrder();
    private long bindingEpoch = -1, windowIdentity, drawSequence = -1;
    private boolean nativeOwned;
    private boolean nativeExitPrepared;
    private long nativeExitRequest=-1;
    private MirrorState mirror;
    private final Runnable changed;
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (token < 0) return;
            send("renew"); worker.postDelayed(this, 450);
        }
    };
    NativeSceneChannel(RuntimeReporter reporter, Runnable changed) {
        this.reporter = reporter; this.changed = changed;
        HandlerThread thread = new HandlerThread("FlymeMotion-scene-ipc"); thread.start();
        worker = new Handler(thread.getLooper());
    }
    /**
     * MiuiHome starts its icon entry animation from its own native onResume. A HOME return
     * therefore withholds the scene's early RESUME request: the swipe still reveals the live
     * desktop wallpaper and HOME's started window, and the real unlock resumes HOME natively.
     * App returns keep the early RESUME. The framework's locked-state hold is not requested.
     */
    void lateDesktopAnimation(boolean selected) {
        if(lateDesktopAnimation==selected)return;lateDesktopAnimation=selected;
        main.post(this::sendRevealIfAllowed);
    }
    boolean homeDeferred() { return homeDeferred; }
    void begin(Context c, boolean canDismiss) {
        if (requested) { update(canDismiss); return; }
        if (firstBeginAt < 0) firstBeginAt = SystemClock.uptimeMillis();
        requested = true; revealing = revealSent = homeDeferred = false; target = "NONE"; nativeExitPrepared=false; backdropReady = nativeReady = false; lastRequested = canDismiss;
        long next = Math.max(SystemClock.uptimeMillis(), latestToken + 1); latestToken = next; expectedToken = next; order.begin(next); nativeOwned = false; bindingEpoch = -1;
        worker.post(() -> {
            if (expectedToken != next || !requested) return;
            try {
                context = c.getApplicationContext(); token = next; dismissible = canDismiss;
                if (!listening) {
                    context.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent intent) {
                            if (!SceneReceivers.fromPackage(c, this, "android")) return;
                            SurfaceControl incoming = intent.getParcelableExtra("mirror", SurfaceControl.class);
                            main.post(() -> receive(intent, incoming));
                        }
                    }, new IntentFilter(STATUS), PERMISSION, worker, Context.RECEIVER_EXPORTED);
                    listening = true;
                }
                send("begin"); worker.removeCallbacks(beat); worker.postDelayed(beat, 450);
            } catch (RuntimeException e) {
                token = -1; nativeReady = false;
                reporter.liveScene = "系统框架通道不可用：" + e.getClass().getSimpleName(); reporter.publish();
            }
        });
    }
    private void receive(Intent intent, SurfaceControl incoming) {
        long id = intent.getLongExtra("token", -1);
        if (intent.getBooleanExtra("restored", false)) {
            Runnable cleanup = retiring.remove(id); if (cleanup != null) cleanup.run();
        }
        if (expectedToken < 0 || id != expectedToken) { if (incoming != null) incoming.release(); return; }
        if (!order.accept(id, intent.getLongExtra("statusSequence", -1))) { if (incoming != null) incoming.release(); return; }
        frameworkKnown = true;
        if (!everHeard) { everHeard = true; reporter.compat.on("框架场景回执", ""); }
        frameworkCompatible = com.zhuanz.flymemotion.TargetBuild.sameProtocol(intent.getStringExtra("frameworkVersion"));
        if (!frameworkCompatible) {
            if (incoming != null) incoming.release(); nativeReady = backdropReady = false;
            reporter.liveScene = "框架图层适配器版本不匹配，需要重启手机加载 " + com.zhuanz.flymemotion.TargetBuild.MODULE_VERSION;
            reporter.publish(); changed.run(); return;
        }
        if (intent.getBooleanExtra("restored", false)) {
            // The server has actually removed this mirror. Keeping requested=true prevents
            // another begin forever; isValid() only proves a local native handle exists.
            if (incoming != null) incoming.release();
            requested = revealing = revealSent = false; expectedToken = -1; nativeReady = backdropReady = nativeOwned = false; revoked = true;
            if (mirror != null) { replaced.add(mirror.surface()); mirror = null; }
            worker.post(() -> { if (token == id) { token = -1; worker.removeCallbacks(beat); } });
            reporter.sceneSession = -1; reporter.liveScene = intent.getStringExtra("status");
            reporter.publish(); changed.run(); return;
        }
        long generation = intent.getLongExtra("mirrorGeneration", -1);
        if (mirror != null && generation >= 0 && generation < mirror.generation()) {
            if (incoming != null) incoming.release(); return;
        }
        if (incoming != null) {
            if (mirror != null && mirror.generation() == generation) incoming.release();
            else {
                // changed() binds/releases composition before the old wrapper can be released.
                MirrorState old = mirror; mirror = new MirrorState(id, generation, incoming);
                nativeReady = false;
                // The render owner binds during pre-draw, not from this async callback.
                // Keep the old wrapper alive until that binding has actually committed.
                if (old != null) replaced.add(old.surface());
            }
        }
        bindingEpoch = intent.getLongExtra("bindingEpoch", -1); windowIdentity = intent.getLongExtra("windowIdentity", 0);
        drawSequence = intent.getLongExtra("drawSequence", -1);
        nativeReady = intent.getBooleanExtra("ready", false) && intent.getBooleanExtra("framePresented", false)
                && bindingEpoch > 0 && windowIdentity > 0 && drawSequence >= 0;
        backdropReady = mirror != null && mirror.generation() == generation
                && intent.getBooleanExtra("backdropReady", false) && intent.getLongExtra("backdropGeneration", -1) > 0
                && intent.getLongExtra("backdropLayer", -1) > 0;
        // Native takeover is a retirement receipt, never an app preview/new-frame receipt.
        nativeOwned = "NATIVE_OWNED".equals(intent.getStringExtra("phase")) && nativeReady
                || intent.getBooleanExtra("nativeTakeover", false);
        nativeExitPrepared=nativeExitRequest>=0 && intent.getLongExtra("exitRequest",-1)==nativeExitRequest
                && intent.getBooleanExtra("nativeExitPrepared",false);
        reporter.sceneSession = id; reporter.returnTarget = intent.getStringExtra("target");
        target = reporter.returnTarget == null ? "NONE" : reporter.returnTarget;
        sendRevealIfAllowed();
        reporter.surfaceGeneration = intent.getLongExtra("generation", 0);
        noteSceneProgress();
        lastReadiness = String.valueOf(intent.getStringExtra("readiness"));
        reporter.sceneReadiness = lastReadiness;
        reporter.liveScene = intent.getStringExtra("status"); reporter.publish(); changed.run();
    }
    /**
     * Whether anything about the scene moved on this receipt. A scene that stops moving after a
     * native exit was requested is not going to be presented; see {@link #frameworkSilent()}.
     */
    private void noteSceneProgress() {
        long mark = bindingEpoch * 31 + drawSequence * 7 + (nativeReady ? 1 : 0) * 3
                + (nativeExitPrepared ? 1 : 0) * 5 + (mirror == null ? -1 : mirror.generation()) * 11
                + (backdropReady ? 1 : 0) * 13;
        if (mark != progressMark) { progressMark = mark; progressAt = SystemClock.uptimeMillis(); }
    }
    MirrorState mirror() { return mirror; }
    private final java.util.List<SurfaceControl> replaced = new java.util.ArrayList<>();
    boolean nativeReady() { return nativeReady; }
    boolean backdropReady() { return backdropReady; }
    boolean consumeRevocation() { boolean result = revoked; revoked = false; return result; }
    boolean incompatibleFramework() { return frameworkKnown && !frameworkCompatible; }
    /**
     * Whether this build will ever present the return window. Two cases, both facts rather than
     * guesses: no receipt at all means the framework's adapter is not running, and a readiness
     * report that names a blocked surface with nothing presented means it is running but cannot
     * show anything. The second is read from what the framework itself reports - a heartbeat that
     * keeps changing says nothing about whether a frame will ever arrive.
     */
    boolean frameworkSilent() {
        long now = SystemClock.uptimeMillis();
        boolean unheard = !everHeard && firstBeginAt >= 0 && now - firstBeginAt > 1500;
        boolean blocked = nativeExitRequest >= 0 && now - nativeExitRequest > 800 && cannotPresent();
        boolean silent = unheard || blocked;
        if (silent && !silentReported) {
            silentReported = true;
            if (unheard) {
                reporter.compat.off("框架场景回执", "框架未回应；解锁交给原生动画，不带壁纸跟随");
                reporter.liveScene = "系统框架适配器未运行：解锁由原生完成";
            } else {
                reporter.compat.off("框架场景回执", "框架报告返回窗口无法呈现；解锁交给原生动画");
                reporter.liveScene = "框架报告返回窗口无法呈现：解锁由原生完成";
            }
            reporter.publish();
        }
        return silent;
    }
    private boolean silentReported;
    /** The framework's own words: nothing presented, and a surface it cannot use. */
    private boolean cannotPresent() {
        String r = lastReadiness;
        if (r == null || r.isEmpty() || r.equals("null")) return false;
        return r.contains("presented=false") && r.contains("blocker=") && !r.contains("blocker=none");
    }
    void mirrorCommitted(long id, long generation, long layer, SurfaceControl parent, int width, int height) {
        for (SurfaceControl old : replaced) old.release(); replaced.clear();
        worker.post(() -> {
            if (token != id) return;
            sendTo("android", new Intent(ACTION).putExtra("command", "mirrorCommitted")
                    .putExtra("token", id).putExtra("mirrorGeneration", generation).putExtra("mirrorLayer", layer)
                    .putExtra("coverParent", parent).putExtra("coverWidth", width).putExtra("coverHeight", height));
        });
    }
    void update(boolean canDismiss) {
        if (!requested || lastRequested == canDismiss) return;
        lastRequested = canDismiss; worker.post(() -> {
            if (token < 0 || dismissible == canDismiss) return;
            dismissible = canDismiss; send("renew");
        });
    }
    void reveal() {
        if (!requested || revealing) return;
        revealing = true;
        sendRevealIfAllowed();
    }
    private void sendRevealIfAllowed() {
        if (!requested || !revealing || revealSent) return;
        // Until the framework names the target, a late HOME must not be resumed early.
        if (!com.zhuanz.flymemotion.core.SceneExitPolicy.requestEarlyResume(lateDesktopAnimation, target)) {
            if ("HOME".equals(target) && !homeDeferred) {
                homeDeferred = true;
                desktopState("deferred", "HOME early resume withheld; native unlock resumes it; gesture=" + expectedToken);
            }
            return;
        }
        revealSent = true;
        if (homeDeferred) { homeDeferred = false; desktopState("cancelled", "setting turned off during gesture; HOME resumed early"); }
        long id = expectedToken;
        worker.post(() -> { if (token == id && id >= 0) send("reveal"); });
    }
    private void desktopState(String state, String detail) {
        reporter.desktopAnimationState = state; reporter.desktopAnimationDetail = detail; reporter.publish();
    }
    boolean nativeOwned() { return nativeOwned; }
    boolean nativeExitPrepared(){return nativeExitPrepared;}
    void requestNativeExit(long generation){
        nativeExitRequest=generation;nativeExitPrepared=false;
        long id=expectedToken;
        worker.post(()->{if(token==id&&id>=0)sendTo("android",new Intent(ACTION)
            .putExtra("command","nativeExit").putExtra("token",id).putExtra("exitRequest",generation));});
    }
    void cancelNativeExit(){
        long generation=nativeExitRequest,id=expectedToken;nativeExitRequest=-1;nativeExitPrepared=false;
        worker.post(()->{if(token==id&&id>=0)sendTo("android",new Intent(ACTION)
            .putExtra("command","cancelNativeExit").putExtra("token",id).putExtra("exitRequest",generation));});
    }
    void handoff() { command("handoff"); }
    void foregroundHidden() { command("foregroundHidden"); }
    private void command(String name) {
        long id = expectedToken, epoch = bindingEpoch;
        worker.post(() -> { if (token == id && id >= 0) sendTo("android",new Intent(ACTION)
                .putExtra("command", name).putExtra("token",id).putExtra("bindingEpoch",epoch)); });
    }
    void end() { end(() -> {}); }
    void end(Runnable cleanup) {
        long oldId = expectedToken;
        requested = false; revealing = revealSent = homeDeferred = false; expectedToken = -1; nativeReady = backdropReady = nativeOwned = nativeExitPrepared = false;
        nativeExitRequest=-1;
        MirrorState old = mirror; mirror = null;
        var oldReplaced = new java.util.ArrayList<>(replaced); replaced.clear();
        Runnable release = () -> { try { cleanup.run(); } finally {
            if (old != null) old.surface().release(); for (SurfaceControl prior : oldReplaced) prior.release();
        } };
        if (oldId < 0) release.run();
        else {
            retiring.put(oldId, release);
            // Only the committed server restore acknowledges removal. A timer is not proof.
            if (retiring.size() > 8) reporter.liveScene = "场景恢复回执缺失，保留覆盖等待原生回收";
        }
        reporter.sceneSession = -1;
        worker.post(() -> {
            if (token == oldId) { send("end"); token = -1; worker.removeCallbacks(beat); }
        });
    }
    private void send(String command) {
        for (String pkg : new String[]{"android", SceneProtocol.WALLPAPER})
            // The framework's HOME locked-state hold is superseded by the withheld RESUME.
            sendTo(pkg, new Intent(ACTION).putExtra("command", command).putExtra("token", token).putExtra("canDismiss", dismissible).putExtra("lateDesktopAnimation",false));
    }
    private void sendTo(String pkg, Intent intent) {
        try {
            if (context != null) context.sendBroadcast(intent.setPackage(pkg), null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        } catch (RuntimeException e) {
            reporter.liveScene = "系统框架通信失败：" + e.getClass().getSimpleName(); reporter.publish();
        }
    }
}

