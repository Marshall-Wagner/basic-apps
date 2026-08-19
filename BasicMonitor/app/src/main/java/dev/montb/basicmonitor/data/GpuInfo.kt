package dev.montb.basicmonitor.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * The GPU model name (e.g. "Adreno 730"), obtained by spinning up a tiny offscreen OpenGL ES
 * context and reading GL_RENDERER, the only portable, no-permission way to get it. The result
 * never changes, so it's queried once and cached.
 */
object GpuInfo {

    @Volatile private var cached: String? = null
    @Volatile private var queried = false

    /** Renderer name, or null if a GL context couldn't be created. Cheap after the first call. */
    fun renderer(): String? {
        if (queried) return cached
        synchronized(this) {
            if (queried) return cached
            cached = query()?.let { clean(it) }
            queried = true
            return cached
        }
    }

    private fun clean(name: String): String =
        name.replace("(TM)", "", ignoreCase = true).replace(Regex("\\s+"), " ").trim()

    private fun query(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val nCfg = IntArray(1)
        if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] == 0) {
            EGL14.eglTerminate(display); return null
        }
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) { EGL14.eglTerminate(display); return null }
        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttrs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, ctx); EGL14.eglTerminate(display); return null
        }

        EGL14.eglMakeCurrent(display, surface, surface, ctx)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)

        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
        renderer
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
