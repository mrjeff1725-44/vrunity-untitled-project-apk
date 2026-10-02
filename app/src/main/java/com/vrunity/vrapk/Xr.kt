package com.vrunity.vrapk

import android.app.Activity

// The headset's own VR runtime, reached through the device's native layer. The
// session, the eye views and the controllers all come straight from the runtime —
// there is no page, browser or WebView involved.
object Xr {
    external fun start(activity: Activity): Boolean
    external fun poll(viewData: FloatArray): Int
    external fun input(stick: FloatArray)
    external fun eyeTexture(eye: Int): Int
    external fun eyeWidth(): Int
    external fun eyeHeight(): Int
    external fun endFrame(): Int
    external fun stop()

    init {
        System.loadLibrary("vrxr")
    }
}
