package com.zhuanz.flymemotion;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Checks actual compositor pixels across sibling surface parents. Emulator only; no MIUI claim. */
public final class SurfaceCompositionInstrumentation extends Instrumentation {
    private final List<SurfaceControl> surfaces = new ArrayList<>();
    private final List<Bitmap> buffers = new ArrayList<>();
    private Dialog dialog;
    private Dialog wallpaperDialog;
    private Activity activity;
    private View decor;
    private FrameLayout content;
    private SurfaceControl wallpaper, wallpaperToken, home;
    private int passed;
    private boolean mirrorProbe;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args); mirrorProbe = args != null && "true".equals(args.getString("mirrorProbe")); start();
    }
    @Override public void onStart() {
        Bundle output = new Bundle(); int result = Activity.RESULT_OK;
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "emulator only");
            activity = startActivitySync(new Intent().setClassName(getTargetContext(),
                    "com.zhuanz.flymemotion.SceneProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                dialog = new Dialog(activity);
                content = new FrameLayout(activity); content.setBackgroundColor(Color.TRANSPARENT);
                dialog.setContentView(content);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog.show();
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                decor = dialog.getWindow().getDecorView();
            });
            idle();
            runOnMainSync(this::createScene); idle();
            checkPixel(.25f, Color.RED, "native lock baseline");
            checkPixel(.80f, Color.RED, "home hidden below lock baseline");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setPosition(wallpaper, 0, -decor.getHeight() * .5f);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(transaction);
                    decor.invalidate();
                }
            }); idle();
            checkPixel(.20f, Color.RED, "real lock surface moves up");
            checkPixel(.75f, Color.BLUE, "stationary home is revealed across different parents");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setPosition(wallpaper, 0, 0);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(transaction); decor.invalidate();
                }
            }); idle();
            checkPixel(.75f, Color.RED, "cancel restores native wallpaper");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setVisibility(home, false).reparent(home, null);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(transaction); decor.invalidate();
                }
            }); idle();
            checkPixel(.75f, Color.RED, "release removes only owned backdrop");
            runOnMainSync(this::createIndependentWindows); idle();
            runOnMainSync(this::createIndependentSurfaces); idle();
            checkPixel(.25f, Color.RED, "lock wallpaper covers app across separate windows");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setPosition(wallpaper, 0, -decor.getHeight() * .5f);
                    transaction.setVisibility(home, false);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(transaction); decor.invalidate();
                }
            }); idle();
            checkPixel(.20f, Color.RED, "moving lock remains above live app window");
            checkPixel(.75f, Color.GREEN, "real app window is exposed after fallback removal");
            runOnMainSync(() -> {
                activity.getWindow().getDecorView().setBackgroundColor(Color.CYAN);
                activity.getWindow().getDecorView().invalidate();
            }); idle();
            checkPixel(.75f, Color.CYAN, "app window continues drawing without screenshot replacement");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setPosition(wallpaper, 0, 0);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(transaction); decor.invalidate();
                }
            }); idle();
            checkPixel(.75f, Color.RED, "cancel covers live app again");
            if (mirrorProbe) verifyLiveMirror();
            verifyCropFade();
            verifyPendingSceneContent();
            output.putString("summary", "PASS " + passed + " actual Surface composition checks; SDK=" + Build.VERSION.SDK_INT
                    + "; mirrorProbe=" + mirrorProbe + "; MIUI hooks not tested");
        } catch (Throwable t) {
            result = Activity.RESULT_CANCELED; output.putString("failure", t.toString());
            output.putString("environment", "SDK=" + Build.VERSION.SDK_INT + "; mirrorProbe=" + mirrorProbe);
        } finally {
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    for (SurfaceControl surface : surfaces) if (surface.isValid()) transaction.reparent(surface, null);
                    transaction.apply();
                }
                for (SurfaceControl surface : surfaces) surface.release();
                for (Bitmap bitmap : buffers) bitmap.recycle();
                if (dialog != null) dialog.dismiss();
                if (wallpaperDialog != null) wallpaperDialog.dismiss();
                if (activity != null) activity.finish();
            });
            finish(result, output);
        }
    }

    private void verifyCropFade() {
        // Same MotionFrame used by production; real buffers/windows still compose underneath.
        for (float p : new float[]{1, .8f, .5f, .02f, 0}) {
            var frame = com.zhuanz.flymemotion.core.MotionFrame.sample(decor.getHeight() * p, 0,
                    decor.getHeight(), true, 1);
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.setPosition(wallpaper, 0, 0).setAlpha(wallpaper, frame.wallpaperAlpha());
                    Method crop = SurfaceControl.Transaction.class.getDeclaredMethod("setWindowCrop", SurfaceControl.class, android.graphics.Rect.class);
                    crop.setAccessible(true); crop.invoke(t, wallpaper, new android.graphics.Rect(0, 0, 16, Math.max(1, Math.round(16 * p))));
                    decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }); idle();
            if (p == 1) checkPixel(.75f, Color.RED, "p=1 covers actual app");
            else {
                checkPixel(.95f, Color.CYAN, "p=" + p + " crop reveals actual app");
                if (p == .8f) checkPixel(.25f, Color.RED, "p=.8 wallpaper remains opaque");
                if (p == .5f) {
                    int[] xy = new int[2]; runOnMainSync(() -> decor.getLocationOnScreen(xy));
                    Bitmap shot = getUiAutomation().takeScreenshot();
                    int pixel = shot.getPixel(xy[0] + decor.getWidth() / 2, xy[1] + decor.getHeight() / 4); shot.recycle();
                    check(Color.red(pixel) > 100 && Color.red(pixel) < 220 && Color.green(pixel) > 30
                            && Color.blue(pixel) > 30, "p=.5 alpha blends two live surfaces");
                    runOnMainSync(() -> {
                        activity.getWindow().getDecorView().setBackgroundColor(Color.YELLOW);
                        activity.getWindow().getDecorView().invalidate();
                    }); idle();
                    checkPixel(.95f, Color.YELLOW, "app updates while wallpaper is partially faded");
                    runOnMainSync(() -> activity.getWindow().getDecorView().setBackgroundColor(Color.CYAN)); idle();
                }
                if (p <= .02f) checkPixel(.25f, Color.CYAN, "p=" + p + " alpha threshold removes lock completely");
            }
        }
        runOnMainSync(() -> {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                Method crop = SurfaceControl.Transaction.class.getDeclaredMethod("setWindowCrop", SurfaceControl.class, android.graphics.Rect.class);
                crop.setAccessible(true); crop.invoke(t, wallpaper, (Object) null);
                t.setAlpha(wallpaper, 1); decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }); idle(); checkPixel(.75f, Color.RED, "cancel restores full crop and alpha");
    }

    /** Flyme mirrors a live producer, then detaches its original token from the display tree. */
    private void verifyLiveMirror() throws ReflectiveOperationException {
        SurfaceControl sourceRoot = wallpaperToken;
        SurfaceControl destination = renderSurface(decor);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setLayer(sourceRoot, 0).apply();
        }
        idle();
        Method mirrorMethod = SurfaceControl.class.getDeclaredMethod("mirrorSurface", SurfaceControl.class);
        mirrorMethod.setAccessible(true);
        SurfaceControl mirrored = (SurfaceControl) mirrorMethod.invoke(null, sourceRoot);
        surfaces.add(mirrored);
        SurfaceControl nativeParent = renderSurface(wallpaperDialog.getWindow().getDecorView());
        try {
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.reparent(mirrored, destination).setLayer(mirrored, -2).setVisibility(mirrored, true)
                            .setScale(mirrored, 1, 1).setPosition(mirrored, 0, 0).setAlpha(mirrored, 1);
                    t.reparent(sourceRoot, null);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
                }
            }); idle();
            try {
                checkPixel(.25f, Color.RED, "live mirror draws after original token is detached");
            } catch (AssertionError failure) {
                try (var descriptor = getUiAutomation().executeShellCommand("dumpsys SurfaceFlinger");
                     var stream = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
                    String dump = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String[] lines = dump.split("\n");
                    for (int i = 0; i < lines.length; i++)
                        if (lines[i].contains("Probe-") || lines[i].contains("MirrorRoot"))
                            for (int j = i; j < Math.min(lines.length, i + 13); j++) android.util.Log.e("MirrorProbe", lines[j]);
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                throw failure;
            }
            checkPixel(.75f, Color.RED, "mirror starts with complete coverage below content");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    Method crop;
                    try {
                        crop = SurfaceControl.Transaction.class.getDeclaredMethod("setWindowCrop", SurfaceControl.class, android.graphics.Rect.class);
                        crop.setAccessible(true); crop.invoke(t, mirrored, new android.graphics.Rect(0, 0, decor.getWidth(), decor.getHeight() / 2));
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
                }
            }); idle();
            checkPixel(.25f, Color.RED, "mirror cropping keeps producer geometry stationary");
            checkPixel(.75f, Color.CYAN, "mirror crop reveals actual return window below");
            runOnMainSync(() -> {
                Bitmap software = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); software.eraseColor(Color.YELLOW);
                Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false); software.recycle(); buffers.add(hardware);
                try (HardwareBuffer buffer = hardware.getHardwareBuffer(); SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.setBuffer(wallpaper, buffer).apply();
                }
            }); idle();
            checkPixel(.25f, Color.YELLOW, "detached producer buffer updates its live mirror");
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.setAlpha(mirrored, 0);
                    decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
                }
            }); idle();
            checkPixel(.25f, Color.CYAN, "fading mirror leaves actual return window visible");
        } finally {
            runOnMainSync(() -> {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.reparent(sourceRoot, nativeParent).reparent(mirrored, null).apply();
                    Bitmap software = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); software.eraseColor(Color.RED);
                    Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false); software.recycle(); buffers.add(hardware);
                    try (HardwareBuffer buffer = hardware.getHardwareBuffer()) { t.setBuffer(wallpaper, buffer).apply(); }
                }
            }); idle();
        }
        checkPixel(.75f, Color.RED, "cancel restores original wallpaper attachment and full geometry");
    }

    private void verifyPendingSceneContent() {
        var gate = new com.zhuanz.flymemotion.core.RevealGate();
        float height = decor.getHeight(); gate.reset(height, 0);
        var motion = com.zhuanz.flymemotion.core.MotionFrame.sample(height * .8f, -500, height, true, 1);
        var frame = gate.compose(motion, height, false, 16);
        var pose = new LockscreenPose(); View[] marker = new View[1]; int[] original = new int[2], windowAt = new int[2];
        runOnMainSync(() -> {
            View v = new View(activity); v.setBackgroundColor(Color.YELLOW);
            FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-1, 64);
            size.topMargin = Math.round(height * .65f); content.addView(v, size); marker[0] = v;
            decor.getLocationOnScreen(windowAt);
        }); idle();
        runOnMainSync(() -> marker[0].getLocationOnScreen(original));
        int x = windowAt[0] + decor.getWidth() / 2;
        checkAt(x, original[1] + 32, Color.YELLOW, "pending scene marker initially visible");
        runOnMainSync(() -> pose.apply(frame.contentY(), decor)); idle();
        checkAt(x, original[1] + Math.round(frame.contentY()) + 32, Color.YELLOW,
                "missing framework receipt retains the complete sheet");
        checkAt(x, original[1] + 32, Color.RED, "pending wallpaper covers the vacated clock area");
        check(frame.wallpaperProgress() == 1 && frame.progress() == .8f && frame.rawHeight() == height * .8f,
                "pending scene protects the backdrop while content follows the finger");
        runOnMainSync(() -> activity.getWindow().getDecorView().setBackgroundColor(Color.MAGENTA)); idle();
        int bottom = windowAt[1] + Math.round(height * .9f);
        checkAt(x, bottom, Color.RED, "unready app remains covered after its buffer changes");
        for (int i = 2; i <= 14; i++) gate.compose(motion, height, true, i * 16);
        var ready = gate.compose(motion, height, true, 240);
        runOnMainSync(() -> {
            pose.apply(ready.contentY(), decor);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                Method crop = SurfaceControl.Transaction.class.getDeclaredMethod("setWindowCrop", SurfaceControl.class, android.graphics.Rect.class);
                crop.setAccessible(true); crop.invoke(t, wallpaper, new android.graphics.Rect(0, 0, 16,
                        Math.max(1, Math.round(16 * ready.wallpaperProgress()))));
                t.setAlpha(wallpaper, ready.wallpaperAlpha());
                decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }); idle();
        checkAt(x, original[1] + Math.round(ready.contentY()) + 32, Color.YELLOW,
                "ready sheet moves content with wallpaper coverage");
        checkAt(x, bottom, Color.MAGENTA, "ready coverage reveals updated actual app buffer");
        var failed = gate.compose(motion, height, false, 256);
        runOnMainSync(() -> {
            pose.apply(failed.contentY(), decor);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                Method crop = SurfaceControl.Transaction.class.getDeclaredMethod("setWindowCrop", SurfaceControl.class, android.graphics.Rect.class);
                crop.setAccessible(true); crop.invoke(t, wallpaper, new android.graphics.Rect(0, 0, 16,
                        Math.max(1, Math.round(16 * failed.wallpaperProgress()))));
                t.setAlpha(wallpaper, failed.wallpaperAlpha()); decor.getRootSurfaceControl().applyTransactionOnDraw(t); decor.invalidate();
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }); idle();
        checkAt(x, original[1] + Math.round(failed.contentY()) + 32, Color.YELLOW,
                "transient receipt retains sheet geometry");
        checkAt(x, bottom, Color.MAGENTA, "transient receipt does not flash full wallpaper back");
        runOnMainSync(() -> { pose.restore(); content.removeView(marker[0]);
            activity.getWindow().getDecorView().setBackgroundColor(Color.CYAN); }); idle();
    }

    private void checkAt(int x, int y, int expected, String label) {
        Bitmap shot = getUiAutomation().takeScreenshot();
        int actual = shot.getPixel(x, y); shot.recycle();
        check(actual == expected, label + " expected=" + Integer.toHexString(expected) + " actual=" + Integer.toHexString(actual));
    }

    private void createScene() {
        try {
            AttachedSurfaceControl root = decor.getRootSurfaceControl();
            SurfaceControl.Builder first = new SurfaceControl.Builder().setName("Probe-SystemUI-parent").setHidden(false);
            SurfaceControl.Builder second = new SurfaceControl.Builder().setName("Probe-Wallpaper-parent").setHidden(false);
            Method container = SurfaceControl.Builder.class.getDeclaredMethod("setContainerLayer"); container.setAccessible(true);
            container.invoke(first); container.invoke(second);
            SurfaceControl systemParent = first.build(), wallpaperParent = second.build();
            surfaces.add(systemParent); surfaces.add(wallpaperParent);
            home = buffered(systemParent, "Probe-HomeWallpaper", Color.BLUE);
            wallpaper = buffered(wallpaperParent, "Probe-RealLockWallpaper", Color.RED);
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.merge(root.buildReparentTransaction(systemParent)).merge(root.buildReparentTransaction(wallpaperParent));
                transaction.setLayer(systemParent, 2).setLayer(wallpaperParent, 3);
                Method relative = SurfaceControl.Transaction.class.getDeclaredMethod("setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
                relative.setAccessible(true); relative.invoke(transaction, home, wallpaper, -1);
                root.applyTransactionOnDraw(transaction); decor.invalidate();
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private void createIndependentWindows() {
        try {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                for (SurfaceControl surface : surfaces) if (surface.isValid()) transaction.reparent(surface, null);
                transaction.apply();
            }
            for (SurfaceControl surface : surfaces) surface.release(); surfaces.clear();
            FrameLayout app = new FrameLayout(activity); activity.setContentView(app);
            activity.getWindow().getDecorView().setBackgroundColor(Color.GREEN);
            // A distinct wallpaper window and a distinct SystemUI-like input window.
            dialog.dismiss();
            wallpaperDialog = new Dialog(activity); wallpaperDialog.setContentView(new FrameLayout(activity));
            wallpaperDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            wallpaperDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            wallpaperDialog.show(); wallpaperDialog.getWindow().setLayout(-1, -1);
            dialog.show(); dialog.getWindow().setLayout(-1, -1); decor = dialog.getWindow().getDecorView();
        } catch (RuntimeException e) { throw e; }
    }
    private void createIndependentSurfaces() {
                try {
                    SurfaceControl appRoot = renderSurface(activity.getWindow().getDecorView());
                    SurfaceControl lockRoot = renderSurface(wallpaperDialog.getWindow().getDecorView());
                    SurfaceControl shadeRoot = renderSurface(decor);
                    home = buffered(shadeRoot, "Probe-StationaryFallback", Color.BLUE);
                     SurfaceControl.Builder tokenBuilder = new SurfaceControl.Builder().setParent(lockRoot)
                             .setName("Probe-LockWallpaperToken").setHidden(false);
                     Method container = SurfaceControl.Builder.class.getDeclaredMethod("setContainerLayer");
                     container.setAccessible(true); container.invoke(tokenBuilder);
                     wallpaperToken = tokenBuilder.build(); surfaces.add(wallpaperToken);
                     wallpaper = buffered(wallpaperToken, "Probe-NativeLockWindow", Color.RED);
                    Method relative = SurfaceControl.Transaction.class.getDeclaredMethod("setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
                    relative.setAccessible(true);
                    try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                         relative.invoke(transaction, wallpaperToken, shadeRoot, -1);
                        relative.invoke(transaction, home, wallpaper, -1);
                        decor.getRootSurfaceControl().applyTransactionOnDraw(transaction); decor.invalidate();
                    }
                    if (!appRoot.isValid()) throw new AssertionError("Live app window missing");
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private SurfaceControl renderSurface(View view) throws ReflectiveOperationException {
        Method getRoot = View.class.getDeclaredMethod("getViewRootImpl"); getRoot.setAccessible(true);
        Object root = getRoot.invoke(view);
        var field = root.getClass().getDeclaredField("mSurfaceControl"); field.setAccessible(true);
        return (SurfaceControl) field.get(root);
    }
    private SurfaceControl buffered(SurfaceControl parent, String name, int color) throws ReflectiveOperationException {
        SurfaceControl.Builder builder = new SurfaceControl.Builder().setName(name).setParent(parent).setHidden(false);
        Method blast = SurfaceControl.Builder.class.getDeclaredMethod("setBLASTLayer"); blast.setAccessible(true); blast.invoke(builder);
        SurfaceControl surface = builder.build(); surfaces.add(surface);
        Bitmap software = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); software.eraseColor(color);
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false); software.recycle(); buffers.add(hardware);
        try (HardwareBuffer buffer = hardware.getHardwareBuffer(); SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setBuffer(surface, buffer).setScale(surface, decor.getWidth() / 16f, decor.getHeight() / 16f).apply();
        }
        return surface;
    }
    private void idle() { waitForIdleSync(); SystemClock.sleep(350); }
    private void checkPixel(float fraction, int expected, String label) {
        int[] location = new int[2]; runOnMainSync(() -> decor.getLocationOnScreen(location));
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("No screenshot");
        int actual = screenshot.getPixel(location[0] + decor.getWidth() / 2, location[1] + Math.round(decor.getHeight() * fraction));
        screenshot.recycle(); check(actual == expected, label + " color=" + Integer.toHexString(actual));
    }
    private void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; }
}
