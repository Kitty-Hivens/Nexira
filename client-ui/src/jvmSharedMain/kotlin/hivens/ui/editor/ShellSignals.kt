package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf

/**
 * Presses claimed by the window and handed to whoever answers them: the editor's toggle and the right rail's fold.
 *
 * The chords are read at window level, where focus does not matter (a key handler on the shell only fires while
 * something inside it holds focus, which the rails take), and what answers them lives deep inside the composition. A
 * tick that only goes up bridges the two: the answering side remembers the tick it last saw and acts on a newer one.
 */
class ShellSignals {
    private val _editToggle = mutableIntStateOf(0)
    val editToggle: State<Int> = _editToggle

    fun requestEditToggle() {
        _editToggle.intValue++
    }

    private val _railToggle = mutableIntStateOf(0)
    val railToggle: State<Int> = _railToggle

    fun requestRailToggle() {
        _railToggle.intValue++
    }
}
