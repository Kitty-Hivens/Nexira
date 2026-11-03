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
                GameSession.input(GameIpc.INPUT_TEXT, a = character)
            }
        }
        return true
    }

    /** The finger that came down first stands in for the mouse, the others are only touches. */
    private var mouseFinger = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                touch(event, index, DOWN)
                if (mouseFinger < 0) {
                    mouseFinger = event.getPointerId(index)
                    mouse(event.getX(index), event.getY(index), down = true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    touch(event, index, MOVE)
                    if (event.getPointerId(index) == mouseFinger) {
                        GameSession.input(GameIpc.INPUT_POINTER_MOVE, x = event.getX(index).toDouble(), y = event.getY(index).toDouble(), source = GameIpc.SOURCE_TOUCH)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                touch(event, index, UP)
                if (event.getPointerId(index) == mouseFinger || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    mouseFinger = -1
                    mouse(event.getX(index), event.getY(index), down = false)
                }
            }
        }
        return true
    }

    private fun touch(event: MotionEvent, index: Int, action: Int) {
        GameSession.input(
            GameIpc.INPUT_TOUCH,
            a = event.getPointerId(index),
            b = action,
            x = event.getX(index).toDouble(),
            y = event.getY(index).toDouble(),
            z = event.getPressure(index).toDouble(),
        )
    }

    private fun mouse(x: Float, y: Float, down: Boolean) {
        GameSession.input(GameIpc.INPUT_POINTER_MOVE, x = x.toDouble(), y = y.toDouble(), source = GameIpc.SOURCE_TOUCH)
        GameSession.input(GameIpc.INPUT_POINTER_BUTTON, a = 0, b = if (down) 1 else 0, source = GameIpc.SOURCE_TOUCH)
    }

    private companion object {
        const val UP = 0
        const val DOWN = 1
        const val MOVE = 2
    }
}
