package com.zhuanz.flymemotion.hook;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Target SDK37 completion ABI. No wait occurs on WM, Binder, UI or RenderThread. */
final class NativePresentationFence {
    private final Method register, latch, present, statsClose, await, valid, fenceClose;
    private final Handler worker;
    /** False when this build lacks the SF completion ABI; commit callbacks stand in for presentation. */
    final boolean presentFence;
    NativePresentationFence(ClassLoader loader) {
        Class<?> stats = com.zhuanz.flymemotion.core.Members.load(loader, "android.view.SurfaceControl$TransactionStats");
        Class<?> fence = com.zhuanz.flymemotion.core.Members.load(loader, "android.hardware.SyncFence");
        // The public wrapper is feature-flag gated on the inspected build; use its exact existing
        // JNI entry and own TransactionStats.close(), as that wrapper itself does.
        register = NativeReflection.methodOrNull(SurfaceControl.class, "nativeAddTransactionCompletedListener", long.class, Consumer.class);
        latch = stats == null ? null : NativeReflection.methodOrNull(stats, "getLatchTimeNanos");
        present = stats == null ? null : NativeReflection.methodOrNull(stats, "getPresentFence");
        statsClose = stats == null ? null : NativeReflection.methodOrNull(stats, "close");
        await = fence == null ? null : NativeReflection.methodOrNull(fence, "await", Duration.class);
        valid = fence == null ? null : NativeReflection.methodOrNull(fence, "isValid");
        fenceClose = fence == null ? null : NativeReflection.methodOrNull(fence, "close");
        presentFence = register != null && latch != null && present != null && statsClose != null && await != null
                && valid != null && fenceClose != null && NativeReflection.fieldOrNull(SurfaceControl.Transaction.class, "mNativeObject") != null;
        HandlerThread thread = new HandlerThread("FlymeMotion-present-fence"); thread.start(); worker = new Handler(thread.getLooper());
    }
    void listen(SurfaceControl.Transaction transaction, BiConsumer<Boolean, Boolean> callback) {
        if (!presentFence) {
            // Older builds: SF committing the draw transaction is the closest public receipt.
            transaction.addTransactionCommittedListener(worker::post, () -> callback.accept(true, true));
            return;
        }
        Consumer<Object> completed = stats -> worker.post(() -> {
            Object fence = null; boolean latched = false, signalled = false;
            try {
                latched = ((Number)NativeReflection.invoke(latch, stats)).longValue() > 0;
                fence = NativeReflection.invoke(present, stats);
                signalled = fence != null && (boolean)NativeReflection.invoke(valid, fence)
                        && (boolean)NativeReflection.invoke(await, fence, Duration.ofMillis(1000));
            } catch (Throwable failure) {
                latched = signalled = false;
                android.util.Log.w("FlymeMotion", "Presentation receipt failed", failure);
            } finally {
                try { if (fence != null) NativeReflection.invoke(fenceClose, fence); }
                catch (Throwable failure) { signalled = false; }
                try { NativeReflection.invoke(statsClose, stats); }
                catch (Throwable failure) { signalled = false; }
            }
            callback.accept(latched, signalled);
        });
        NativeReflection.invoke(register, null, ((Number)NativeReflection.get(transaction, "mNativeObject")).longValue(), completed);
    }
}
