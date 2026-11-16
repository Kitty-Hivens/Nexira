package hivens.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.onEventThread
import hivens.ui.theme.NxTheme
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
                NxTheme(Themes.default, dark = true) {
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
}
