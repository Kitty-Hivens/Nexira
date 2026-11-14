package hivens.ui.layers

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Asked by the button the press came from rather than by the pressed-button flags:
 * on X11 the thumb buttons reach AWT as buttons 6 and 7, which Compose passes on
 * with no flags at all, so a check of the back and forward flags let them through.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.pressesMainButton(): Boolean {
    val pressed = button ?: return true
    return pressed == PointerButton.Primary || pressed == PointerButton.Secondary || pressed == PointerButton.Tertiary
}
