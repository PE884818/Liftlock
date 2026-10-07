package com.zhuanz.flymemotion;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only flat app window for compositor fixtures. */
public final class SceneProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(new FrameLayout(this));
    }
}
