package hivens.ui.nx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The string field under the editing shortcuts a desktop keyboard has, pressed the way a window
 * sends them: after each one the field and its caller hold the same text by the next frame, and a
 * copy or a cut lands on the test's clipboard.
 *
 * The names say Ctrl as Windows and Linux have it. On macOS the shortcuts go with the command key
 * and the word moves and erases with the option key, the way the framework maps them there, see
 * [onMac]. Every shortcut here means something there too, so none is skipped.
 *
 * End and Shift and End stop before spaces at the end of a one-line field, where the framework
 * puts the end of the line, so typing after End on a line ending in a space lands before the
 * space. That is the framework's text field and none of this one's, and no test here leans on it.
 */
class NxBasicTextFieldShortcutsTest {

    /** A one-line field whose caller keeps every edit, holding [LINE] with the caret at its end. */
    private class Line {
        var text by mutableStateOf("")
        val heard = mutableListOf<String>()
        val field = FieldInput { modifier ->
            NxBasicTextField(
                value = text,
                onValueChange = { heard += it; text = it },
                singleLine = true,
                modifier = modifier,
            )
        }
    }

    private fun line(body: Line.() -> Unit) = onEdt {
        val line = Line()
        try {
            line.field.focus()
            line.text = LINE
            line.field.settle()
            line.field.clipboard.text = BEFORE
            line.body()
        } finally {
            line.field.close()
        }
    }

    /** Field and caller both hold [expected] at the next frame, and still do once everything has settled. */
    private fun Line.agree(expected: String, what: String) {
        field.settle(1)
        assertEquals(expected, field.shown, "the field after $what")
        assertEquals(expected, text, "the caller after $what")
        field.settle()
        assertEquals(expected, field.shown, "the field once settled after $what")
        assertEquals(expected, text, "the caller once settled after $what")
    }

    private fun Line.press(key: Key, shortcut: Boolean = false, word: Boolean = false, shift: Boolean = false) {
        field.press(key, shortcut = shortcut, word = word, shift = shift)
    }

    /** What is selected in the field. */
    private val Line.selected: String get() = field.shown.substring(field.selection.min, field.selection.max)

    /** The paste shortcut, then frames until the clipboard's text has landed in the field. */
    private fun Line.paste() {
        val before = field.shown
        press(Key.V, shortcut = true)
        field.settleUntil("the paste") { field.shown != before }
    }

    /** The shortcut with [key], then frames until the clipboard no longer holds what it held. */
    private fun Line.toClipboard(key: Key) {
        val before = field.clipboard.text
        press(key, shortcut = true)
        field.settleUntil("the clipboard write") { field.clipboard.text != before }
    }

    @Test
    fun `Ctrl and A selects the whole line and typing replaces it`() = line {
        press(Key.A, shortcut = true)
        field.settle(1)
        assertEquals(LINE, selected)
        field.commit("x")
        agree("x", "typing over the whole line")
    }

    @Test
    fun `Ctrl and C copies the selection and leaves the line as it is`() = line {
        press(Key.A, shortcut = true)
        toClipboard(Key.C)
        assertEquals(LINE, field.clipboard.text)
        agree(LINE, "a copy")
    }

    @Test
    fun `Ctrl and X moves the selection to the clipboard`() = line {
        press(Key.A, shortcut = true)
        toClipboard(Key.X)
        assertEquals(LINE, field.clipboard.text)
        agree("", "a cut")
    }

    @Test
    fun `Ctrl and V pastes at the caret`() = line {
        field.clipboard.text = " delta"
        paste()
        agree("$LINE delta", "a paste")
    }

    @Test
    fun `a paste replaces the selection`() = line {
        field.clipboard.text = "omega"
        repeat("gamma".length) { press(Key.DirectionLeft, shift = true) }
        field.settle(1)
        assertEquals("gamma", selected)
        paste()
        agree("alpha beta omega", "a paste over the selection")
    }

    @Test
    fun `Ctrl and Z takes a paste back`() = line {
        field.clipboard.text = " delta"
        paste()
        agree("$LINE delta", "a paste")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo of the paste")
    }

    @Test
    fun `Ctrl and Z takes a paste over a selection back`() = line {
        field.clipboard.text = "omega"
        press(Key.A, shortcut = true)
        paste()
        agree("omega", "a paste over the whole line")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo of the paste")
    }

    @Test
    fun `Ctrl and Z takes a cut back and the clipboard keeps what was cut`() = line {
        press(Key.A, shortcut = true)
        toClipboard(Key.X)
        agree("", "a cut")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo of the cut")
        assertEquals(LINE, field.clipboard.text)
    }

    @Test
    fun `Ctrl and Y does again what Ctrl and Z took back`() = line {
        field.commit(" delta")
        agree("$LINE delta", "typing")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo")
        press(Key.Y, shortcut = true)
        agree("$LINE delta", "a redo with Ctrl and Y")
    }

    @Test
    fun `Ctrl, Shift and Z does again what Ctrl and Z took back`() = line {
        field.commit(" delta")
        agree("$LINE delta", "typing")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo")
        press(Key.Z, shortcut = true, shift = true)
        agree("$LINE delta", "a redo with Ctrl, Shift and Z")
    }

    @Test
    fun `an undo and a redo of a cut reach the caller each time`() = line {
        press(Key.A, shortcut = true)
        toClipboard(Key.X)
        agree("", "a cut")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo of the cut")
        press(Key.Y, shortcut = true)
        agree("", "a redo of the cut")
    }

    @Test
    fun `Shift and the arrows grow and shrink the selection by a letter`() = line {
        press(Key.DirectionLeft, shift = true)
        press(Key.DirectionLeft, shift = true)
        press(Key.DirectionLeft, shift = true)
        field.settle(1)
        assertEquals("mma", selected)
        press(Key.DirectionRight, shift = true)
        field.settle(1)
        assertEquals("ma", selected)
        field.commit("X")
        agree("alpha beta gamX", "typing over the selection")
    }

    @Test
    fun `Ctrl and the arrows move the caret a word at a time`() = line {
        press(Key.DirectionLeft, word = true)
        field.settle(1)
        assertEquals(LINE.indexOf("gamma"), field.selection.start, "Ctrl and Left lands at the start of the last word")
        press(Key.DirectionLeft, word = true)
        field.commit("_")
        agree("alpha _beta gamma", "typing at the start of the second word")
        press(Key.MoveHome)
        press(Key.DirectionRight, word = true)
        field.commit("!")
        agree("alpha! _beta gamma", "typing after Ctrl and Right from the start")
    }

    @Test
    fun `Ctrl, Shift and the arrows select a word at a time`() = line {
        press(Key.DirectionLeft, word = true, shift = true)
        field.settle(1)
        assertEquals("gamma", selected)
        press(Key.DirectionLeft, word = true, shift = true)
        field.settle(1)
        assertEquals("beta gamma", selected)
        press(Key.DirectionRight, word = true, shift = true)
        field.settle(1)
        assertEquals(" gamma", selected, "Ctrl, Shift and Right gives back the word taken last")
        toClipboard(Key.X)
        assertEquals(" gamma", field.clipboard.text)
        agree("alpha beta", "a cut of a word")
    }

    @Test
    fun `Ctrl and Backspace erases the word before the caret`() = line {
        press(Key.Backspace, word = true)
        agree("alpha beta ", "Ctrl and Backspace at the end")
        press(Key.Backspace, word = true)
        agree("alpha ", "Ctrl and Backspace again")
    }

    @Test
    fun `Ctrl and Delete erases the word after the caret`() = line {
        press(Key.MoveHome)
        press(Key.Delete, word = true)
        agree(" beta gamma", "Ctrl and Delete at the start")
    }

    @Test
    fun `an undo of Ctrl and Backspace reaches the caller`() = line {
        press(Key.Backspace, word = true)
        agree("alpha beta ", "Ctrl and Backspace")
        press(Key.Z, shortcut = true)
        agree(LINE, "an undo of the erased word")
    }

    @Test
    fun `Home and End put the caret at the ends of the line`() = line {
        press(Key.MoveHome)
        field.commit("^")
        agree("^$LINE", "typing after Home")
        press(Key.MoveEnd)
        field.commit("$")
        agree("^$LINE$", "typing after End")
    }

    @Test
    fun `Shift with Home and End select to the ends of the line`() = line {
        repeat("gamma".length) { press(Key.DirectionLeft) }
        press(Key.MoveHome, shift = true)
        field.settle(1)
        assertEquals("alpha beta ", selected)
        field.commit("new ")
        agree("new gamma", "typing over Shift and Home")
        press(Key.MoveHome)
        press(Key.MoveEnd, shift = true)
        field.settle(1)
        assertEquals("new gamma", selected)
        toClipboard(Key.C)
        assertEquals("new gamma", field.clipboard.text)
        agree("new gamma", "a copy of Shift and End")
    }

    // The letters before the next frame: the caller still holds the selected text when the
    // replacement arrives.
    @Test
    fun `typing over a selection right behind Ctrl and A reaches the caller whole`() = line {
        press(Key.A, shortcut = true)
        field.commit("n")
        field.commit("ew")
        agree("new", "typing over the selection with no frame in between")
    }

    // No frame between the key and the check: the caller is given an undo inside it.
    @Test
    fun `Ctrl and Z reaches the caller inside the undo`() = line {
        field.commit(" delta")
        field.settle()
        press(Key.Z, shortcut = true)
        assertEquals(LINE, field.shown)
        assertEquals(LINE, text, "the caller holds what the undo left before any frame")
    }

    @Test
    fun `Ctrl and Y and Ctrl, Shift and Z reach the caller inside the redo`() = line {
        field.commit(" delta")
        field.settle()
        press(Key.Z, shortcut = true)
        field.settle()
        press(Key.Y, shortcut = true)
        assertEquals("$LINE delta", text, "the caller holds what Ctrl and Y redid before any frame")
        press(Key.Z, shortcut = true)
        field.settle()
        press(Key.Z, shortcut = true, shift = true)
        assertEquals("$LINE delta", text, "the caller holds what Ctrl, Shift and Z redid before any frame")
        agree("$LINE delta", "the redo")
    }

    @Test
    fun `an undo and a redo each reach the caller once`() = line {
        field.commit(" delta")
        field.settle()
        press(Key.Z, shortcut = true)
        field.settle()
        press(Key.Y, shortcut = true)
        field.settle()
        assertEquals(listOf("$LINE delta", LINE, "$LINE delta"), heard)
    }

    private companion object {
        const val LINE = "alpha beta gamma"

        /** What the clipboard holds before a test copies anything. */
        const val BEFORE = "from before"
    }
}
