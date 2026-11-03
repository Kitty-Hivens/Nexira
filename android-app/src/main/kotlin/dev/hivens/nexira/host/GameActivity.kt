package dev.hivens.nexira.host

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The screen the game draws on: one surface, edge to edge, with the keys and the finger passed on to the game.
 *
 * The game lives in another process, so this holds nothing of it: the surface, the display and the input go through
 * [GameSession], and the screen closes itself when the game ends. The finger is a mouse for now, one pointer.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var view: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        view = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)
        // The decor view exists only after the content does
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        GameSession.screenUp(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        announceDisplay(view.width, view.height)
        GameSession.surfaceChanged(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        announceDisplay(width, height)
        GameSession.surfaceChanged(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        GameSession.surfaceChanged(null)
    }

    private fun announceDisplay(width: Int, height: Int) {
        val refresh = display?.refreshRate ?: 60f
        GameSession.displayChanged(width, height, refresh, resources.displayMetrics.densityDpi.toFloat())
    }

    override fun onResume() {
        super.onResume()
        GameSession.input(GameIpc.INPUT_FOCUS, a = 1)
    }

    override fun onPause() {
        GameSession.input(GameIpc.INPUT_FOCUS, a = 0)
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving the screen ends the game the way closing its window would, so it can save
        GameSession.input(GameIpc.INPUT_CLOSE)
        GameSession.screenDown(this)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        GameSession.input(GameIpc.INPUT_KEY, a = event.keyCode, b = if (down) 1 else 0, meta = event.metaState)
        if (down) {
            val character = event.unicodeChar
            if (character > 0 && !Character.isISOControl(character)) {
                GameSession.input(GameIpc.INPUT_CHAR, a = character)
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toDouble()
        val y = event.y.toDouble()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                GameSession.input(GameIpc.INPUT_CURSOR, x = x, y = y)
                GameSession.input(GameIpc.INPUT_BUTTON, a = 0, b = 1)
            }
            MotionEvent.ACTION_MOVE -> GameSession.input(GameIpc.INPUT_CURSOR, x = x, y = y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                GameSession.input(GameIpc.INPUT_CURSOR, x = x, y = y)
                GameSession.input(GameIpc.INPUT_BUTTON, a = 0, b = 0)
            }
        }
        return true
    }
}
