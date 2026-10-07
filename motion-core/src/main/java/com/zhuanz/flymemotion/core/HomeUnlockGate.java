package com.zhuanz.flymemotion.core;

/** Delays a HOME visual notification; never alters native authentication state. */
public final class HomeUnlockGate {
    private long session=-1;
    private int homeUid=-1;
    private boolean enabled, completed;
    public synchronized void arm(long id,int uid,boolean selected){
        if(session!=id){session=id;completed=false;}
        homeUid=uid;enabled=selected&&id>=0&&uid>=0;
    }
    public synchronized boolean hold(int uid,boolean locked){return enabled&&!completed&&!locked&&uid==homeUid;}
    public synchronized boolean finish(long id){if(session!=id)return false;completed=true;return true;}
    public synchronized long session(){return session;}
    public synchronized void cancel(){enabled=false;completed=true;}
}
