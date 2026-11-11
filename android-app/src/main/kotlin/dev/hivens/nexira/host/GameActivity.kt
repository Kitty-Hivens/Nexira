package dev.hivens.nexira.host

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.hypot

/**
 * The screen the game draws on: one surface, edge to edge, with the keys, the mouse and the fingers passed on to the game.
 *
 * The game lives in another process, so this holds nothing of it: the surface, the display and the input go through
 * [GameSession], and the screen closes itself when the game ends.
 *
 * A mouse is a mouse, captured while the game looks around. Fingers go to the TouchController mod when the game has it,
 * and over a menu the first one is the pointer as well. A game without the mod gets the first finger as a mouse: a drag
 * turns the view while the game looks around, a hold is the left button and a tap the right. A tap with two fingers
 * brings up the keyboard there, since nothing in the game can ask for it.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var view: GameSurface
    private var cursorMode = GameIpc.CURSOR_NORMAL
    private var touchSlop = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        view = GameSurface(this).apply {
            holder.addCallback(this@GameActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, event -> capturedMouse(event) }
            // The keyboard covers the bottom of the game, where a chat line is, so the game moves up out of its way
            setOnApplyWindowInsetsListener { surface, insets ->
                surface.translationY = -insets.getInsets(WindowInsets.Type.ime()).bottom.toFloat()
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                // A keyboard the player put away themselves ends the typing too, or the next key of a hardware
                // keyboard would bring it back instead of reaching the game
                if (imeWasVisible && !imeVisible && takesText) this@GameActivity.softKeyboard(false)
                imeWasVisible = imeVisible
                insets
            }
        }
        setContentView(view)
        // Android 15 and later draw edge to edge by themselves, the older ones the app supports have to be told
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
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

    /**
     * Puts the game into typing or takes it out. The surface takes text only meanwhile: while it does, Android hands
     * every key to the input method first, and the keyboard turns a key into text, so a key the game binds (T for the
     * chat) never reached it as a key. Outside typing a hardware keyboard's keys go to the game directly, and
     * [dispatchKeyEvent] still passes their characters on.
     */
    internal fun softKeyboard(show: Boolean) {
        val keyboard = getSystemService(InputMethodManager::class.java)
        if (view.takesText != show) {
            view.takesText = show
            // The input method asks whether the view takes text when input starts, so it starts again
            keyboard.restartInput(view)
        }
        if (show) {
            val _ = view.requestFocus()
            val _ = keyboard.showSoftInput(view, 0)
        } else {
            val _ = keyboard.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private var imeWasVisible = false

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

    /** A second finger came down soon after the first and neither has gone anywhere, so far a tap with two fingers. */
    private var twoFingerTap = false
    private var firstFingerAt = 0L

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
        GameSession.touchController()?.let { touchController ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    fingers[event.getPointerId(event.actionIndex)]?.let { touchController.liftPointer(it) }
                MotionEvent.ACTION_CANCEL -> touchController.clearPointers()
            }
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
        val touchController = GameSession.touchController()
        if (touchController == null) {
            if (event.pointerCount == 1) {
                firstFingerAt = event.eventTime
                twoFingerTap = false
            } else if (event.pointerCount == 2 && event.eventTime - firstFingerAt < TWO_FINGER_TAP_MS) {
                twoFingerTap = true
            } else {
                twoFingerTap = false
            }
        }
        if (cursorMode == GameIpc.CURSOR_CAPTURED) {
            if (touchController == null && lookFinger == NO_FINGER) {
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
                twoFingerTap = false
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
                !lookMoved && !cancelled && !twoFingerTap -> {
                    fingerButton(RIGHT, down = true)
                    fingerButton(RIGHT, down = false)
                }
            }
            lookHolding = false
        }
        // The last finger of a tap with two is the end of it
        if (twoFingerTap && !cancelled && event.pointerCount == 1) {
            twoFingerTap = false
            if (event.eventTime - firstFingerAt < TWO_FINGER_TAP_MS * 2) softKeyboard(!view.takesText)
        }
    }

    private fun touch(event: MotionEvent, index: Int, action: Int) {
        val finger = fingers[event.getPointerId(index)] ?: return
        if (action != UP && view.width > 0 && view.height > 0) {
            GameSession.touchController()?.pointer(finger, event.getX(index) / view.width, event.getY(index) / view.height)
        }
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
        const val TWO_FINGER_TAP_MS = 250L

        const val LEFT = 0
        const val RIGHT = 1
        const val MIDDLE = 2
        const val BUTTON_4 = 3
        const val BUTTON_5 = 4
    }
}

/**
 * The game's surface, which is also where a soft keyboard types while [takesText] is set. Text arrives as a keyboard
 * commits it and goes to the game as typed characters, and what the keyboard is still composing is shown by typing and
 * rubbing out.
 */
private class GameSurface(context: Context) : SurfaceView(context) {

    /** Whether the game is typing, set by [GameActivity.softKeyboard]. */
    var takesText = false

    override fun onCheckIsTextEditor(): Boolean = takesText

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!takesText) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return GameInputConnection(this)
    }
}

private class GameInputConnection(view: SurfaceView) : BaseInputConnection(view, false) {

    private var composing = 0

    private fun rubOut(count: Int) {
        repeat(count) {
            press(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun press(keyCode: Int) {
        GameSession.input(GameIpc.INPUT_KEY, a = keyCode, b = 1)
        GameSession.input(GameIpc.INPUT_KEY, a = keyCode, b = 0)
    }

    /** Returns how many characters the game now counts. */
    private fun type(text: CharSequence): Int {
        var count = 0
        text.toString().codePoints().forEach { codepoint ->
            count++
            if (codepoint == '\n'.code) press(KeyEvent.KEYCODE_ENTER) else GameSession.input(GameIpc.INPUT_TEXT, a = codepoint)
        }
        return count
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        rubOut(composing)
        composing = type(text)
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = 0
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        rubOut(composing)
        composing = 0
        val _ = type(text)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        rubOut(beforeLength)
        repeat(afterLength) { press(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        press(KeyEvent.KEYCODE_ENTER)
        return true
    }
}
