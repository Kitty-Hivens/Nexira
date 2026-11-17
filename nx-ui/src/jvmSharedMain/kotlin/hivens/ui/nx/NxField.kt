package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

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
                Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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
 * What a field built on the framework's text state takes from [rememberFieldState]: [text] is its
 * state, and [input] is its input transformation.
 */
@Stable
class FieldState internal constructor(
    val text: TextFieldState,
    val input: InputTransformation,
)

/**
 * The text state a field edits, kept in step with the caller's string.
 *
 * An edit the person makes reaches the caller while it is being made, through [FieldState.input],
 * the way the string field called its caller from inside the edit. Whatever reads the caller's
 * value next, the Enter after an input method's word or a press on a button, finds the edit
 * there. [filter] sees the edit first, and what it keeps is what the caller is given. An undo or
 * a redo, which skips the input transformation, reaches the caller as it lands as well.
 *
 * After every composition the field and the caller's value are compared again, also when the
 * value composes the same as before: a caller that took a line and cleared it before the next
 * frame passes the same empty value twice, and the field still has to empty. See [FieldSync].
 */
@Composable
fun rememberFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    filter: InputTransformation? = null,
): FieldState {
    val state = rememberTextFieldState(value)
    val latestValue = rememberUpdatedState(value)
    val latestChange = rememberUpdatedState(onValueChange)
    val sync = remember(state) { FieldSync(state, { latestValue.value }, { latestChange.value(it) }) }
    val input = remember(filter, sync) { filter?.then(sync) ?: sync }
    DisposableEffect(sync) {
        val watch = Snapshot.registerGlobalWriteObserver { sync.onWrite() }
        onDispose { watch.dispose() }
    }
    val shown = state.text.toString()
    // In the frame the value composed in, so a line the caller emptied is empty at that frame.
    SideEffect { sync.reconcile(value, shown) }
    LaunchedEffect(value, shown, sync.handOvers) {
        withFrames(ANSWER_FRAMES)
        sync.takeBackRefused(value)
    }
    return FieldState(state, input)
}

/**
 * Keeps a field's text and its caller's string in step.
 *
 * As the field's last input transformation it hands each edit the person makes to the caller
 * inside the edit. Whether the caller changed any state while it took the edit tells what its
 * next value means. One that did has answered: whatever it composes next is its word, the edit
 * kept, rewritten, refused or the line cleared after an Enter, and the field shows it at once.
 * One that did not either refuses or answers later through a flow, so its value trails the
 * typing: a value that is one of the field's own earlier edits is it catching up, and setting
 * the field to it would erase what was typed since. Such a caller gets [ANSWER_FRAMES] frames
 * before an edit it still has not taken is taken back.
 *
 * A change the person makes that skips the input transformation, an undo or a redo, is found by
 * [onWrite] as the text state is written, and handed over then, so an Enter queued behind an undo
 * sends what the undo left. [reconcile] finds whatever that missed as text the field holds that it
 * never handed over, and hands it over from there.
 */
private class FieldSync(
    private val state: TextFieldState,
    private val held: () -> String,
    private val give: (String) -> Unit,
) : InputTransformation {
    private val echo = FieldEcho()

    /** One more for every edit handed over, so the field compares again even when the caller's value composes unchanged. */
    var handOvers by mutableIntStateOf(0)
        private set

    /** The text the field holds as far as this knows: the last one handed over or set here. */
    private var known = state.text.toString()

    /** Whether the caller changed state while it took the newest edit handed over. */
    private var answered = false

    /** What the caller held before the edits it has not answered yet. */
    private var before: String? = null

    /** The thread the field is composed and edited on. A write on any other thread is not the field's. */
    private val owner = Thread.currentThread()

    /** The field's text as [onWrite] last saw it. The state holds a new one for every change, so a write that left it alone is told by identity. */
    private var seen: CharSequence = state.text

    override fun TextFieldBuffer.transformInput() {
        hand(asCharSequence().toString())
    }

    private fun hand(typed: String) {
        known = typed
        val value = held()
        // Compared with what the caller is about to hold, not with the value it
        // shows yet: erasing back to a value it has not caught up from must still
        // be sent, or the caller keeps the longer text the field no longer shows.
        if (typed == (echo.lastSent() ?: value)) return
        if (echo.lastSent() == null) before = value
        echo.sending(typed)
        var wrote = false
        Snapshot.observe(writeObserver = { wrote = true }) { give(typed) }
        answered = wrote
        handOvers++
    }

    /**
     * Called after every write to state outside a composition. An undo or a redo writes the text
     * without passing [transformInput], and is handed over here, as it lands. Text that is already
     * [known], an edit handed over inside it or a value set here, is not handed over again.
     */
    fun onWrite() {
        if (Thread.currentThread() !== owner) return
        // A write can land while a layout reads under observation, which must not come to depend on this field.
        val now = Snapshot.withoutReadObservation { state.text }
        if (now === seen) return
        seen = now
        val text = now.toString()
        if (text != known) hand(text)
    }

    /**
     * Brings the field and the caller together once [value] has composed while the field shows
     * [shown]. A caller still at its value from before the edits it has not answered is left to
     * [takeBackRefused].
     */
    fun reconcile(value: String, shown: String) {
        when {
            shown != known -> hand(shown)
            value == shown -> settled()
            answered || echo.lastSent() == null -> show(value)
            value == before -> Unit
            else -> echo.onValue(value, shown)?.let(::show)
        }
    }

    /** Called [ANSWER_FRAMES] frames after nothing changed: a caller still at [value], its value from before the edits, refused them. */
    fun takeBackRefused(value: String) {
        if (echo.lastSent() != null && !answered && value == before && state.text.toString() != value) show(value)
    }

    private fun settled() {
        echo.clear()
        before = null
        answered = false
    }

    private fun show(value: String) {
        settled()
        known = value
        state.setTextAndPlaceCursorAtEnd(value)
    }
}

private suspend fun withFrames(count: Int) = repeat(count) { withFrameNanos { } }

/**
 * What a field has handed its caller and not yet seen come back.
 *
 * A slow caller's value trails the typing. A value that is one of the field's own earlier edits is
 * the caller catching up, and setting the field to it erased whatever was typed in between: on a
 * machine slow to draw, a word typed at an ordinary pace lost letters. Only a value the field
 * never sent, a rewrite or a change from outside, replaces what it shows.
 */
internal class FieldEcho {
    private val sent = ArrayDeque<String>()

    /** The newest edit handed to the caller that it has not answered yet, or null. */
    fun lastSent(): String? = sent.lastOrNull()

    /** The field is handing [typed] to the caller. */
    fun sending(typed: String) {
        sent.addLast(typed)
    }

    /** The caller holds what the field shows, so nothing handed over is still on its way. */
    fun clear() {
        sent.clear()
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
}

/** How many frames a caller that has not answered an edit gets before the edit counts as refused. */
private const val ANSWER_FRAMES = 8

@Preview
@Composable
private fun NxFieldDarkPreview() = FieldStates(dark = true)

@Preview
@Composable
private fun NxFieldLightPreview() = FieldStates(dark = false)

@Composable
private fun FieldStates(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(360.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NxField(value = "Survival world", onValueChange = {}, placeholder = "World name")
            NxField(value = "", onValueChange = {}, placeholder = "Search packs")
            NxField(
                value         = "First line\nSecond line",
                onValueChange = {},
                placeholder   = "Notes",
                singleLine    = false,
                modifier      = Modifier.fillMaxWidth().height(80.dp),
            )
        }
    }
}
