package hivens.ui.layers

import androidx.compose.ui.input.key.KeyEvent

/** Android counts the repeats of a held key on every event it sends. */
internal actual fun KeyEvent.repeatCountOrNull(): Int? = nativeKeyEvent.repeatCount
