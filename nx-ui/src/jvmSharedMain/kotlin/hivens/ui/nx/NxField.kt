package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.surface.SurfaceKind

/**
 * One text input: a [BasicTextField] inside a [SurfaceKind.Field] surface with a
 * [placeholder] and a cursor in the theme's lead colour. The one
 * field screens compose, replacing raw `BasicTextField` + a hand-rolled background.
 * [singleLine] off makes it a multi-line area; pass height through [modifier].
 * [textStyle] null falls back to bodySmall in the primary text colour.
 *
 * The caller keeps the text: [value] in, [onValueChange] out. The field edits a text state of its
 * own and follows the caller both ways (see [rememberFieldState]), so a caller that rewrites or
 * refuses an edit sees the field show what it kept.
 */
@Composable
fun NxField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle? = null,
) {
    NxSurface(
        kind     = SurfaceKind.Field,
        shape    = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        // Inside the surface, so the ink is the one that reads on the field itself.
        val ts = textStyle ?: MaterialTheme.typography.bodySmall.copy(color = NxInk.main)
        NxBasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = singleLine,
            textStyle     = ts,
            cursorBrush   = SolidColor(NxColor.lead()),
            modifier      = Modifier.fillMaxWidth(),
            // The inset is inside the field, not around it. Around it, a press on the
            // field's own edge landed on nothing, and to the shell it was a press
            // outside the field, which let go of the caret.
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = Spacing.s10, vertical = Spacing.s8)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = ts.copy(color = NxInk.quiet))
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * The text state a field edits, kept in step with the caller's string.
 *
 * The caller changed the text from outside: the state follows. Typing goes to the caller, and once
 * the caller has had time to answer, an edit it did not keep is taken back, because it would
 * otherwise stay on screen over a value that never took it. Which of the caller's values are only
 * catching up with the typing is [FieldEcho]'s to tell.
 */
@Composable
fun rememberFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val latestValue = rememberUpdatedState(value)
    val latestChange = rememberUpdatedState(onValueChange)
    val echo = remember { FieldEcho() }

    LaunchedEffect(value) {
        echo.onValue(value, state.text.toString())?.let(state::setTextAndPlaceCursorAtEnd)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            // Compared with what the caller is about to hold, not with the value it
            // shows yet: erasing back to a value it has not caught up from must still
            // be sent, or the caller keeps the longer text the field no longer shows.
            if (typed == (echo.lastSent() ?: latestValue.value)) return@collect
            val before = latestValue.value
            echo.sending(typed)
            latestChange.value(typed)
            // The caller gets a few frames to take the edit before it counts as
            // refused. The first frame resumes this before it recomposes the caller,
            // and a caller that answers through a flow takes longer still.
            for (frame in 0 until ANSWER_FRAMES) {
                withFrameNanos { }
                if (latestValue.value != before) break
            }
            echo.settle(typed, before, latestValue.value, state.text.toString())?.let(state::setTextAndPlaceCursorAtEnd)
        }
    }
    return state
}

/**
 * What a field has handed its caller and not yet seen come back.
 *
 * The caller's value trails the typing by at least a recomposition. A value that is one of the
 * field's own earlier edits is the caller catching up, and setting the field to it erased whatever
 * was typed in between: on a machine slow to draw, a word typed at an ordinary pace lost letters.
 * Only a value the field never sent, a rewrite or a change from outside, replaces what it shows.
 */
internal class FieldEcho {
    private val sent = ArrayDeque<String>()

    /** The newest edit handed to the caller that it has not answered yet, or null. */
    fun lastSent(): String? = sent.lastOrNull()

    /** The field is handing [typed] to the caller. */
    fun sending(typed: String) {
        sent.addLast(typed)
    }

    /** The caller's value became [value] while the field shows [shown]: the text to show instead, or null to keep it. */
    fun onValue(value: String, shown: String): String? {
        val i = sent.indexOf(value)
        if (i >= 0) {
            repeat(i + 1) { sent.removeFirst() }
            return null
        }
        sent.clear()
        return if (shown != value) value else null
    }

    /**
     * After the caller had time to answer [typed], sent while its value was [before]: the text to
     * take the field back to when the caller refused the edit, or null. Nothing is taken back once
     * more has been typed, since that edit is on its way to the caller too.
     */
    fun settle(typed: String, before: String, value: String, shown: String): String? {
        if (shown != typed || value != before || sent.lastOrNull() != typed) return null
        sent.clear()
        return before
    }
}

/** How many frames a caller has to take an edit before it counts as refused. */
private const val ANSWER_FRAMES = 8
