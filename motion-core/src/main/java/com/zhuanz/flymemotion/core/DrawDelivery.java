package com.zhuanz.flymemotion.core;

/** An actual client draw delivery, captured at WMS's client boundary. */
public record DrawDelivery(long session, long binding, long window, long surface,
                           int sequence, boolean bufferTransaction) {
    public boolean consume(ScenePresentation presentation, long id, long epoch, long windowId, long layer) {
        return session == id && binding == epoch && window == windowId && surface == layer
                && presentation.matches(id,epoch,windowId,layer)
                && presentation.drawn(epoch,sequence,bufferTransaction);
    }
}
