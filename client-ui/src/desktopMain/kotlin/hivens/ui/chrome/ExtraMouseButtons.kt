package hivens.ui.chrome

import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

/** What a press of a mouse button past the third one means. */
internal enum class ExtraButton { Back, Forward, ScrollLeft, ScrollRight, None }

/**
 * Reads an AWT button number past the third one.
 *
 * AWT numbers them differently per toolkit, and the difference is not cosmetic.
 * On Windows and macOS the side buttons arrive as 4 and 5. On X11 the server spends
 * its buttons 4 to 7 on the wheel, AWT turns 4 and 5 into wheel events and hands
 * the rest on two lower: the sideways wheel (a tilted wheel, a touchpad swiped
 * sideways under XWayland) arrives as 4 and 5, and the side buttons as 6 and 7.
 * Reading 4 and 5 as back and forward everywhere turned a sideways swipe into a
 * jump through history, and left the sideways wheel scrolling nothing.
 */
internal fun extraButton(button: Int, x11: Boolean): ExtraButton = when {
    x11 && button == 4 -> ExtraButton.ScrollLeft
    x11 && button == 5 -> ExtraButton.ScrollRight
    button == 4 || button == 6 -> ExtraButton.Back
    button == 5 || button == 7 -> ExtraButton.Forward
    else -> ExtraButton.None
}

/**
 * Whether this event happened in [window]. True when there is no window to compare
 * against, so a shell that has not published its window yet still answers.
 */
internal fun MouseEvent.isFrom(window: Window?): Boolean {
    if (window == null) return true
    val source = component ?: return false
    val owner = source as? Window ?: SwingUtilities.getWindowAncestor(source)
    return owner === window
}

/** Whether AWT is running on X11, where the extra buttons are numbered the X way. */
internal val awtOnX11: Boolean by lazy { Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit" }

/**
 * The sideways wheel, given back to the window as the wheel it is.
 *
 * X11 delivers it as a button press, which nothing that scrolls ever sees. The same
 * motion as a wheel turn with Shift held is what scrolling content already reads
 * as sideways, so it is re-sent as that, to the component the press came from.
 */
internal fun resendAsSidewaysWheel(press: MouseEvent, toRight: Boolean) {
    val target: Component = press.component ?: return
    val rotation = if (toRight) 1 else -1
    target.dispatchEvent(
        MouseWheelEvent(
            target,
            MouseEvent.MOUSE_WHEEL,
            press.`when`,
            press.modifiersEx or InputEvent.SHIFT_DOWN_MASK,
            press.x,
            press.y,
            press.xOnScreen,
            press.yOnScreen,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            rotation,
            rotation.toDouble(),
        ),
    )
}
