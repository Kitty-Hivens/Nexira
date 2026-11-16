package hivens.ui.nx

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.ImeAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The string field typed into through its input session, the way a keyboard and an input
 * method reach it, with the caller keeping the string.
 */
class NxBasicTextFieldInputTest {

    /** A one-line field that submits on Enter and clears itself after, as a command line does. */
    private class Line(accept: (String) -> Boolean = { true }) {
        var text by mutableStateOf("")
        val heard = mutableListOf<String>()
        val submitted = mutableListOf<String>()

        val field = FieldInput { modifier ->
            NxBasicTextField(
                value = text,
                onValueChange = { heard += it; if (accept(it)) text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = KeyboardActionHandler {
                    submitted += text
                    text = ""
                },
                modifier = modifier,
            )
        }
    }

    private fun line(accept: (String) -> Boolean = { true }, body: Line.() -> Unit) = onEdt {
        val line = Line(accept)
        try {
            line.field.focus()
            line.body()
        } finally {
            line.field.close()
        }
    }

    // An input method hands a word over and the Enter behind it arrives before the next
    // frame. The caller heard of the word only after the Enter had read its text.
    @Test
    fun `Enter right after a word sees the word`() = line {
        field.commit("say hi")
        field.press(Key.Enter)
        field.settle()
        assertEquals(listOf("say hi"), submitted)
        assertEquals("", field.shown, "the line is empty after it was sent")
        assertEquals("", text)
    }

    @Test
    fun `each edit reaches the caller as it is made`() = line {
        field.commit("a")
        assertEquals("a", text, "the caller holds the letter before anything is drawn")
        field.commit("b")
        assertEquals("ab", text)
        field.settle()
        assertEquals(listOf("a", "ab"), heard)
    }

    @Test
    fun `a word an input method holds open stays open while the caller follows it`() = line {
        field.compose("ka")
        field.settle()
        assertEquals("ka", text, "the caller sees the open word, as it did with the string field")
        assertTrue(field.composing, "the field kept the word open")
        field.compose("か")
        field.settle()
        assertTrue(field.composing)
        assertEquals("か", field.shown)
        field.commit("借")
        field.settle()
        assertFalse(field.composing)
        assertEquals("借", text)
        assertEquals("借", field.shown)
    }

    @Test
    fun `a Cyrillic word typed letter by letter arrives whole`() = line {
        "привет".forEach { field.commit(it.toString()) }
        field.press(Key.Enter)
        field.settle()
        assertEquals(listOf("привет"), submitted)
    }

    @Test
    fun `the caller clearing the line from outside empties the field`() = line {
        field.commit("draft")
        field.settle()
        text = ""
        field.settle()
        assertEquals("", field.shown)
        field.commit("x")
        field.settle()
        assertEquals("x", text)
        assertEquals("x", field.shown)
    }

    @Test
    fun `a filter keeps what it refuses out of the field and away from the caller`() = onEdt {
        var text by mutableStateOf("")
        val heard = mutableListOf<String>()
        val digits = InputTransformation.byValue { _, proposed -> proposed.filter(Char::isDigit).take(4) }
        FieldInput { modifier ->
            NxBasicTextField(
                value = text,
                onValueChange = { heard += it; text = it },
                singleLine = true,
                inputTransformation = digits,
                modifier = modifier,
            )
        }.use { field ->
            field.focus()
            field.commit("1a")
            assertEquals("1", field.shown, "the letter never reached the field")
            field.commit("x2")
            field.settle()
            assertEquals("12", field.shown)
            field.commit("code: 345")
            field.settle()
            assertEquals("1234", field.shown, "a paste keeps its digits up to the limit")
            assertEquals("1234", text)
            assertEquals(listOf("1", "12", "1234"), heard)
        }
    }

    @Test
    fun `the line is empty at the first frame after a word and Enter`() = line {
        field.commit("say hi")
        field.press(Key.Enter)
        field.settle(1)
        assertEquals(listOf("say hi"), submitted)
        assertEquals("", field.shown, "the caller emptied the line, and the field with it")
    }

    // The caller's value composes empty before and after: only the field knows it moved.
    @Test
    fun `a letter typed at the next frame after a word and Enter starts a new line`() = line {
        field.commit("say hi")
        field.press(Key.Enter)
        field.settle(1)
        field.commit("l")
        field.press(Key.Enter)
        field.settle()
        assertEquals(listOf("say hi", "l"), submitted)
    }

    @Test
    fun `a value from the caller lands with the caret at its end`() = line {
        text = "abc"
        field.settle(1)
        field.commit("d")
        assertEquals("abcd", field.shown)
    }

    @Test
    fun `a letter erased before the next frame is erased for the caller too`() = line {
        field.commit("a")
        field.edit(BackspaceCommand())
        assertEquals("", text, "the caller already holds the empty line")
        field.settle()
        assertEquals("", text)
        assertEquals("", field.shown)
    }

    @Test
    fun `a value set from outside after a letter typed and erased shows`() = line {
        field.commit("x")
        field.edit(BackspaceCommand())
        field.settle(1)
        text = "x"
        field.settle(1)
        assertEquals("x", field.shown)
    }

    @Test
    fun `a letter the caller refuses is taken back`() = line({ it.all(Char::isDigit) }) {
        field.commit("1")
        field.settle()
        field.commit("a")
        field.settle()
        assertEquals("1", text)
        assertEquals("1", field.shown)
    }

    @Test
    fun `one commit reaches the caller once`() = line {
        field.commit("abc")
        field.settle()
        field.compose("d")
        field.settle()
        field.commit("d")
        field.settle()
        assertEquals(listOf("abc", "abcd"), heard)
    }

    @Test
    fun `an undo right behind a letter reaches the caller by the next frame`() = line {
        text = "abc"
        field.settle()
        field.commit("d")
        field.press(Key.Z, ctrl = true)
        field.settle(2)
        assertEquals("abc", field.shown, "the undo took the letter off the field")
        assertEquals("abc", text, "and the caller follows it")
    }

    @Test
    fun `an undo a frame after the letter reaches the caller`() = line {
        text = "abc"
        field.settle()
        field.commit("d")
        field.settle(1)
        field.press(Key.Z, ctrl = true)
        field.settle(2)
        assertEquals("abc", field.shown)
        assertEquals("abc", text)
    }

    /**
     * A field whose caller changes no state while it takes an edit and answers [LAG] frames
     * later, as one that answers through a flow does. Its value trails the typing.
     */
    private class SlowLine {
        var text by mutableStateOf("")
        private val inFlight = ArrayDeque<Pair<String, Int>>()

        val field = FieldInput { modifier ->
            LaunchedEffect(Unit) {
                while (true) {
                    withFrameNanos { }
                    for (i in inFlight.indices) inFlight[i] = inFlight[i].first to inFlight[i].second - 1
                    while (inFlight.firstOrNull()?.second?.let { it <= 0 } == true) text = inFlight.removeFirst().first
                }
            }
            NxBasicTextField(
                value = text,
                onValueChange = { inFlight.addLast(it to LAG) },
                singleLine = true,
                modifier = modifier,
            )
        }
    }

    private fun slowLine(body: SlowLine.() -> Unit) = onEdt {
        val line = SlowLine()
        try {
            line.field.focus()
            line.body()
        } finally {
            line.field.close()
        }
    }

    // Two frames between letters, so a wait shorter than the caller's answer runs out
    // between them and takes the letter back.
    @Test
    fun `a caller that answers some frames late loses no letters`() = slowLine {
        "abc".forEach {
            field.commit(it.toString())
            field.settle(2)
            assertEquals(it.toString(), field.shown.takeLast(1), "a letter was taken back before the caller answered")
        }
        assertEquals("abc", field.shown)
        field.settle()
        assertEquals("abc", text)
        assertEquals("abc", field.shown)
    }

    // Both edits went out, and the caller answering them one after the other must not
    // leave the first behind as an edit still on its way.
    @Test
    fun `a late caller's own value equal to a letter typed and erased earlier shows`() = slowLine {
        field.commit("x")
        field.edit(BackspaceCommand())
        field.settle()
        assertEquals("", text)
        text = "x"
        field.settle(1)
        assertEquals("x", field.shown)
    }

    private companion object {
        const val LAG = 3
    }
}
