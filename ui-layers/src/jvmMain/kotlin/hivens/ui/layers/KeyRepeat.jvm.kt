package hivens.ui.layers

import androidx.compose.ui.input.key.KeyEvent

/** AWT keeps no count of a key's repeats: a held key arrives as one press after another. */
internal actual fun KeyEvent.repeatCountOrNull(): Int? = null
