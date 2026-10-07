package com.zhuanz.flymemotion.core;

/** Main-thread request ordering, including end-before-begin and initialization delay. */
public final class SceneRequestOrder {
    private long token=-1,binding=-1;
    private boolean active;
    public boolean begin(long nextToken,long nextBinding) {
        if(nextToken<0 || nextBinding<0 || nextToken<token
                || nextToken==token && nextBinding<=binding)return false;
        token=nextToken;binding=nextBinding;active=true;return true;
    }
    public boolean end(long endedToken,long endedBinding) {
        if(endedToken<0 || endedBinding<0 || endedToken<token
                || endedToken==token && endedBinding<binding)return false;
        token=endedToken;binding=endedBinding;active=false;return true;
    }
    public boolean current(long expectedToken,long expectedBinding) {
        return active && token==expectedToken && binding==expectedBinding;
    }
}
