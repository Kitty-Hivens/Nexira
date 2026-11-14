package hivens.ui.chrome

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import hivens.ui.layers.DismissStack
import kotlinx.coroutines.launch

/**
 * Escape as back, for a host whose platform does not make back of an Escape that
 * nothing used.
 *
 * Android's Compose takes an Escape that reaches the root of its window as a step of
 * the focus out of whatever holds it, and reports the key as used, so the system
 * never makes the back it makes of an unused Escape. A press only moved the focus,
 * and with the shell holding it, did nothing at all: no layer closed and back never
 * came. The host's key hook calls this after everything focused has had the key and
 * before that root sees it. The first press of an Escape nothing used becomes
 * [back], which closes the top of the window's dismiss stack or moves the history,
 * and the press is taken whole, its release included.
 *
 * The rest of Escape stays the stack's (see [DismissStack.onKeyEvent]): the repeats
 * of a held key are taken and do nothing, and so is the half of a press the host
 * used to take the keyboard out of a field. The window's preview hook still has to
 * call [DismissStack.onPreviewKeyEvent], which tells a press from its repeats.
 */
internal class EscapeAsBack(private val stack: DismissStack, private val back: () -> Unit) {

    // A press made back, whose release is taken with it.
    private var taken = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.Escape) return false
        if (stack.onKeyEvent(event)) return true
        return when (event.type) {
            KeyEventType.KeyDown -> {
                taken = true
                back()
                true
            }
            KeyEventType.KeyUp -> taken.also { taken = false }
            else -> false
        }
    }
}

/**
 * Keeps the keyboard focus somewhere under this node, so the key hooks on it go on
 * hearing keys: a host reads its keys from hooks that are asked only while something
 * under them holds the focus.
 *
 * Whenever nothing under it holds the focus, as after Escape takes it out of a
 * field or a dialog opens with nothing focused, it takes the focus itself a frame
 * later, and only if nothing under it has taken it meanwhile. A layer lets the focus
 * go as it opens and its field asks for it right after, and taking it back without
 * looking took the caret out of that field.
 */
@Composable
internal fun Modifier.keepsFocus(): Modifier {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val held = remember { FocusHeld() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return this
        .focusRequester(focus)
        .onFocusChanged {
            held.value = it.hasFocus
            if (!it.hasFocus) {
                scope.launch {
                    withFrameNanos { }
                    if (!held.value) focus.requestFocus()
                }
            }
        }
        .focusable()
}

/** Whether anything under a [keepsFocus] node holds the focus, as its last focus event said. */
private class FocusHeld {
    var value = false
}
