package com.zhuanz.flymemotion.hook;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import java.lang.reflect.Method;

/** The platform swap every GLES wallpaper renderer ends a frame with; stable across OEM builds. */
final class EGL14Swap {
    private EGL14Swap() {}
    static Method method() throws NoSuchMethodException {
        return EGL14.class.getDeclaredMethod("eglSwapBuffers", EGLDisplay.class, EGLSurface.class);
    }
}
