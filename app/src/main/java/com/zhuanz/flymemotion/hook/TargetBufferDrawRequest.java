package com.zhuanz.flymemotion.hook;

import com.zhuanz.flymemotion.core.BufferDrawRequest;

/** Mutate only the selected WindowState's native next-buffer request sequence. */
final class TargetBufferDrawRequest {
    private TargetBufferDrawRequest() {}
    static boolean preserve(Object window) {
        int old=NativeReflection.integer(window,"mBufferSeqId");
        int next=BufferDrawRequest.preserve(old,NativeReflection.integer(window,"mSyncSeqId"));
        if(next==old)return false;
        NativeReflection.set(window,"mBufferSeqId",next);return true;
    }
}
