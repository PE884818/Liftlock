package com.zhuanz.flymemotion.core;

/** A per-gesture, one-shot gate for native launcher animation calls, never authentication. */
public final class DesktopAnimationGate {
    private long generation=-1;
    private boolean enabled,released,prepared,play,consumed;
    public void begin(long token,boolean selected){generation=token;enabled=selected;released=prepared=play=consumed=false;}
    public boolean accepts(long token,boolean home){return token==generation&&enabled&&home&&!consumed;}
    public void prepared(long token){if(token==generation&&enabled&&!consumed)prepared=true;}
    public void play(long token){if(token==generation&&enabled&&!consumed)play=true;}
    public void released(long token){if(token==generation&&enabled)released=true;}
    public boolean consume(long token){if(token!=generation||!enabled||consumed||!released||!prepared||!play)return false;consumed=true;return true;}
    public void cancel(){enabled=false;prepared=play=released=false;consumed=true;}
}
