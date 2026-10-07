package com.zhuanz.flymemotion.core;

/** SDK37 buffer requests must survive a later, coalesced metadata-only resize. */
public final class BufferDrawRequest {
    private BufferDrawRequest() {}
    public static int preserve(int bufferSequence, int syncSequence) {
        if (syncSequence == Integer.MAX_VALUE) throw new IllegalStateException("Draw sequence exhausted");
        return Math.max(bufferSequence, syncSequence + 1);
    }
    public static boolean pending(boolean selected, boolean active, boolean authenticated,
                                  boolean covered, boolean ready, long acceptedDraw) {
        return selected && active && authenticated && covered && !ready && acceptedDraw < 0;
    }
}
