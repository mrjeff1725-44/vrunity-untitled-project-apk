package com.vrunity.vrapk

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

// A native Android VR game: fullscreen, awake, stereoscopic, and playable with
// nothing but the headset's own buttons.
class MainActivity : Activity() {
    private lateinit var surface: VrSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = VrSurfaceView(this)
        setContentView(surface)
        fullscreen()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        surface.startSensors()
        fullscreen()
    }

    override fun onPause() {
        surface.stopSensors()
        surface.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    // Volume up walks forward, volume down walks back — reachable by touch while
    // the device is inside a headset, so the game needs no controller.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            surface.walk(1f)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            surface.walk(-1f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
