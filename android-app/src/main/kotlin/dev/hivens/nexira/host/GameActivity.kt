package dev.hivens.nexira.host

import android.app.Activity
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.hypot

/**
 * The screen the game draws on: one surface, edge to edge, with the keys, the mouse and the fingers passed on to the game.
 *
 * The game lives in another process, so this holds nothing of it: the surface, the display and the input go through
 * [GameSession], and the screen closes itself when the game ends.
 *
 * A mouse is a mouse, captured while the game looks around. A finger stands in for one: over a menu it is the pointer,
 * and while the game looks around a drag turns the view, a hold is the left button and a tap the right.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var view: SurfaceView
    private var cursorMode = GameIpc.CURSOR_NORMAL
    private var touchSlop = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        view = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, event -> capturedMouse(event) }
        }
        setContentView(view)
        // The decor view exists only after the content does
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        GameSession.screenUp(this)
        cursorMode = GameSession.cursorMode()
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
        GameSession.focusChanged(true)
    }

    override fun onPause() {
        GameSession.focusChanged(false)
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving the screen ends the game the way closing its window would, so it can save
        GameSession.input(GameIpc.INPUT_CLOSE)
        GameSession.screenDown(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system lets go of a captured pointer when the window loses focus, and it comes back with the focus
        if (hasFocus) applyPointerCapture()
    }

    internal fun cursorModeChanged(mode: Int) {
        cursorMode = mode
        applyPointerCapture()
    }

    private fun applyPointerCapture() {
        if (cursorMode == GameIpc.CURSOR_CAPTURED) {
            val _ = view.requestFocus()
            view.requestPointerCapture()
        } else {
            view.releasePointerCapture()
        }
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

    // ---- the mouse ----

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) mouse(event) else super.onGenericMotionEvent(event)

    private fun mouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE ->
                GameSession.input(GameIpc.INPUT_POINTER_MOVE, x = event.x.toDouble(), y = event.y.toDouble(), source = GameIpc.SOURCE_DEVICE)
            else -> return mouseButtonOrWheel(event)
        }
        return true
    }

    /** Captured, a mouse reports how far it moved instead of where it is, in every sample the event carries. */
    private fun capturedMouse(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return mouseButtonOrWheel(event)
        var dx = event.x
        var dy = event.y
        for (sample in 0 until event.historySize) {
            dx += event.getHistoricalX(sample)
            dy += event.getHistoricalY(sample)
        }
        GameSession.input(GameIpc.INPUT_RELATIVE, x = dx.toDouble(), y = dy.toDouble(), source = GameIpc.SOURCE_DEVICE)
        return true
    }

    private fun mouseButtonOrWheel(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> mouseButton(event, down = true)
            MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event, down = false)
            MotionEvent.ACTION_SCROLL -> GameSession.input(
                GameIpc.INPUT_SCROLL,
                x = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                y = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(),
            )
            else -> return false
        }
        return true
    }

    private fun mouseButton(event: MotionEvent, down: Boolean) {
        val button = when (event.actionButton) {
            MotionEvent.BUTTON_PRIMARY -> LEFT
            MotionEvent.BUTTON_SECONDARY -> RIGHT
            MotionEvent.BUTTON_TERTIARY -> MIDDLE
            MotionEvent.BUTTON_BACK -> BUTTON_4
            MotionEvent.BUTTON_FORWARD -> BUTTON_5
            else -> return
        }
        GameSession.input(GameIpc.INPUT_POINTER_BUTTON, a = button, b = if (down) 1 else 0, meta = event.metaState, source = GameIpc.SOURCE_DEVICE)
    }

    // ---- the fingers ----

    /** The finger that stands in for the mouse over a menu. */
    private var mouseFinger = NO_FINGER

    /** The finger that turns the view while the game looks around, and what it has done so far. */
    private var lookFinger = NO_FINGER
    private var lookX = 0f
    private var lookY = 0f
    private var lookStartX = 0f
    private var lookStartY = 0f
    private var lookMoved = false
    private var lookHolding = false

    private val startHolding = Runnable {
        if (lookFinger != NO_FINGER && !lookMoved) {
            lookHolding = true
            fingerButton(LEFT, down = true)
        }
    }

    /**
     * Android reuses a pointer id as soon as a finger lifts, and the touch controller wants an index that only grows
     * and starts at one, so every touch gets a number of its own that is never given out again.
     */
    private val fingers = HashMap<Int, Int>()
    private var nextFinger = 1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A mouse button held down arrives here too, its press and release come as events of their own
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return if (event.actionMasked == MotionEvent.ACTION_MOVE) mouse(event) else true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> fingerDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) fingerMoved(event, index)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> lift(event, event.actionIndex, cancelled = false)
            // A cancel ends every finger at once
            MotionEvent.ACTION_CANCEL -> for (index in 0 until event.pointerCount) lift(event, index, cancelled = true)
        }
        return true
    }

    private fun fingerDown(event: MotionEvent, index: Int) {
        val pointer = event.getPointerId(index)
        fingers[pointer] = nextFinger++
        touch(event, index, DOWN)
        val x = event.getX(index)
        val y = event.getY(index)
        if (cursorMode == GameIpc.CURSOR_CAPTURED) {
            if (lookFinger == NO_FINGER) {
                lookFinger = pointer
                lookX = x
                lookY = y
                lookStartX = x
                lookStartY = y
                lookMoved = false
                lookHolding = false
                view.postDelayed(startHolding, HOLD_MS)
            }
        } else if (mouseFinger == NO_FINGER) {
            mouseFinger = pointer
            fingerAsMouse(x, y, down = true)
        }
    }

    private fun fingerMoved(event: MotionEvent, index: Int) {
        touch(event, index, MOVE)
        val pointer = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        if (pointer == mouseFinger) {
            GameSession.input(GameIpc.INPUT_POINTER_MOVE, x = x.toDouble(), y = y.toDouble(), source = GameIpc.SOURCE_TOUCH)
        }
        if (pointer == lookFinger) {
            if (!lookMoved && hypot(x - lookStartX, y - lookStartY) > touchSlop) {
                lookMoved = true
                view.removeCallbacks(startHolding)
            }
            if (lookMoved) {
                GameSession.input(GameIpc.INPUT_RELATIVE, x = (x - lookX).toDouble(), y = (y - lookY).toDouble(), source = GameIpc.SOURCE_TOUCH)
            }
            lookX = x
            lookY = y
        }
    }

    private fun lift(event: MotionEvent, index: Int, cancelled: Boolean) {
        touch(event, index, UP)
        val pointer = event.getPointerId(index)
        fingers.remove(pointer)
        if (pointer == mouseFinger) {
            mouseFinger = NO_FINGER
            fingerAsMouse(event.getX(index), event.getY(index), down = false)
        }
        if (pointer == lookFinger) {
            lookFinger = NO_FINGER
            view.removeCallbacks(startHolding)
            when {
                lookHolding -> fingerButton(LEFT, down = false)
                !lookMoved && !cancelled -> {
                    fingerButton(RIGHT, down = true)
                    fingerButton(RIGHT, down = false)
                }
            }
            lookHolding = false
        }
    }

    private fun touch(event: MotionEvent, index: Int, action: Int) {
        val finger = fingers[event.getPointerId(index)] ?: return
        GameSession.input(
            GameIpc.INPUT_TOUCH,
            a = finger,
            b = action,
            x = event.getX(index).toDouble(),
            y = event.getY(index).toDouble(),
            z = event.getPressure(index).toDouble(),
        )
    }

    private fun fingerAsMouse(x: Float, y: Float, down: Boolean) {
        GameSession.input(GameIpc.INPUT_POINTER_MOVE, x = x.toDouble(), y = y.toDouble(), source = GameIpc.SOURCE_TOUCH)
        fingerButton(LEFT, down)
    }

    private fun fingerButton(button: Int, down: Boolean) {
        GameSession.input(GameIpc.INPUT_POINTER_BUTTON, a = button, b = if (down) 1 else 0, source = GameIpc.SOURCE_TOUCH)
    }

    private companion object {
        const val UP = 0
        const val DOWN = 1
        const val MOVE = 2
        const val NO_FINGER = -1
        const val HOLD_MS = 300L

        const val LEFT = 0
        const val RIGHT = 1
        const val MIDDLE = 2
        const val BUTTON_4 = 3
        const val BUTTON_5 = 4
    }
}
