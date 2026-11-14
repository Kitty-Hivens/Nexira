package hivens.ui.layers

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed

/** A finger or a mouse's own buttons: Android reports the thumb buttons by their flags. */
internal actual fun PointerEvent.pressesMainButton(): Boolean = !buttons.isBackPressed && !buttons.isForwardPressed
