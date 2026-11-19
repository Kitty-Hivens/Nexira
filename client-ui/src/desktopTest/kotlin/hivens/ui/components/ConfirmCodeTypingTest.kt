package hivens.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.onEventThread
import hivens.ui.theme.NxThemed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The second factor's code typed into the dialog: only digits get in, six of them at
 * most, and Enter sends a whole code and nothing short of one.
 */
class ConfirmCodeTypingTest {

    private fun dialog(body: TypingScene.(submitted: List<String>) -> Unit) = onEventThread {
        val submitted = mutableListOf<String>()
        TypingScene(600, 500) {
            ShippedLanguageProvider("en") {
                NxThemed(Themes.default, dark = true) {
                    LayerHost(rememberWindowLayers(), Modifier.fillMaxSize()) {
                        ConfirmCodeDialog(onDismiss = {}, onSubmit = { submitted += it })
                    }
                }
            }
        }.use { scene ->
            // The dialog puts the caret in its field as it opens.
            scene.settle(30)
            scene.body(submitted)
        }
    }

    @Test
    fun `letters typed between the digits stay out of the code`() = dialog { submitted ->
        commit("12a")
        commit("b3")
        settle()
        assertEquals("123", fieldText())
        commit("456")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun `a pasted line keeps its digits and no more than the code has`() = dialog { submitted ->
        commit("code: 123 456 789")
        settle()
        assertEquals("123456", fieldText())
        commit("7")
        settle()
        assertEquals("123456", fieldText(), "a seventh digit does not get in")
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun `Enter on a code short of six digits sends nothing`() = dialog { submitted ->
        commit("12345")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(emptyList(), submitted)
        commit("x")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(emptyList(), submitted, "a letter does not make the code whole")
    }

    @Test
    fun `a letter never reaches the field, not even before the next frame`() = dialog { _ ->
        commit("12a")
        assertEquals("12", shown)
        commit("-3 4")
        assertEquals("1234", shown)
    }

    @Test
    fun `a seventh digit never reaches the field`() = dialog { _ ->
        commit("1234567")
        assertEquals("123456", shown)
        commit("8")
        assertEquals("123456", shown)
    }

    @Test
    fun `a pasted code with spaces and dashes keeps its digits only`() = dialog { submitted ->
        commit("12 34-56")
        assertEquals("123456", shown)
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    // Char.isDigit takes these too, and the server reads none of them as a digit.
    @Test
    fun `fullwidth and Arabic-Indic digits are not digits of the code`() = dialog { _ ->
        commit("\uff11\uff12\u0663\u0664")
        assertEquals("", shown)
        settle()
        assertEquals("", fieldText())
    }

    // A password manager types the code and the Enter in one go.
    @Test
    fun `Enter right behind the sixth digit sends the code`() = dialog { submitted ->
        commit("123456")
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    /** The digits the dialog's cells draw, which is the code the dialog holds. */
    private fun TypingScene.cells(): String =
        scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.withDescendants() }
            .filter { SemanticsProperties.EditableText !in it.config }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
            .filter { it.length == 1 && it[0] in '0'..'9' }
            .joinToString("")

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }

    /** The field and the cells both hold [expected] at the next frame. */
    private fun TypingScene.holds(expected: String, what: String) {
        settle(1)
        assertEquals(expected, shown, "the field after $what")
        assertEquals(expected, cells(), "the cells after $what")
    }

    @Test
    fun `a code pasted with spaces keeps its digits and Enter sends it`() = dialog { submitted ->
        clipboard.text = "123 456"
        paste()
        holds("123456", "a paste")
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun `a code cut with Ctrl and X is gone and comes back with Ctrl and V`() = dialog { submitted ->
        commit("123456")
        settle()
        press(Key.A, shortcut = true)
        pressToClipboard(Key.X)
        assertEquals("123456", clipboard.text)
        holds("", "a cut")
        paste()
        holds("123456", "a paste of the cut code")
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun `Ctrl and C copies the code and leaves it in the field`() = dialog { _ ->
        clipboard.text = "from before"
        commit("123456")
        settle()
        press(Key.A, shortcut = true)
        pressToClipboard(Key.C)
        assertEquals("123456", clipboard.text)
        holds("123456", "a copy")
    }

    @Test
    fun `a pasted line over the whole code replaces it with its digits`() = dialog { submitted ->
        clipboard.text = "code: 98 76 54"
        commit("111111")
        settle()
        press(Key.A, shortcut = true)
        paste()
        holds("987654", "a paste over the code")
        press(Key.Enter)
        settle()
        assertEquals(listOf("987654"), submitted)
    }

    @Test
    fun `a paste with no digits in it leaves the code as it is`() = dialog { _ ->
        clipboard.text = "no digits"
        commit("12")
        settle()
        press(Key.V, shortcut = true)
        settleAWhile()
        holds("12", "a paste with no digits")
    }

    @Test
    fun `Ctrl and Z takes a paste back and Ctrl and Y puts it again`() = dialog { submitted ->
        clipboard.text = "34 56"
        commit("12")
        settle()
        paste()
        holds("123456", "a paste")
        press(Key.Z, shortcut = true)
        holds("12", "an undo of the paste")
        press(Key.Y, shortcut = true)
        holds("123456", "a redo of the paste")
        press(Key.Enter)
        settle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun `Home, Shift and Left and Ctrl and Backspace edit the code where the caret is`() = dialog { _ ->
        commit("3450")
        settle()
        press(Key.MoveHome)
        commit("12")
        holds("123450", "typing after Home")
        press(Key.MoveEnd)
        press(Key.DirectionLeft, shift = true)
        commit("6")
        holds("123456", "typing over the last digit")
        press(Key.Backspace, word = true)
        holds("", "Ctrl and Backspace")
        press(Key.Z, shortcut = true)
        holds("123456", "an undo of Ctrl and Backspace")
    }
}
