package hivens.ui.editor.props

import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.theme.NxTheme
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A whole-number prop with no range typed into: only digits get in, and nothing typed around a refused one is lost. */
class NumberRowTypingTest {

    private fun row(body: TypingScene.(changes: List<JsonElement>) -> Unit) = onEventThread {
        val changes = mutableListOf<JsonElement>()
        TypingScene(400, 80) {
            ShippedLanguageProvider("en") {
                NxTheme(Themes.default, dark = true) {
                    PropFieldRow(
                        label = "Size",
                        element = Long.serializer().descriptor,
                        annotations = emptyList(),
                        current = JsonPrimitive(5),
                        onChange = { changes += it },
                    )
                }
            }
        }.use { scene ->
            scene.settle()
            scene.focusField()
            scene.body(changes)
        }
    }

    @Test
    fun `a letter never reaches the field and the digit typed right after it stays`() = row { changes ->
        commit("a")
        assertEquals("5", shown, "the letter is not in the field, not even before the next frame")
        commit("2")
        assertEquals("52", shown)
        settle()
        assertEquals("52", fieldText())
        assertEquals(JsonPrimitive(52L), changes.last())
    }

    @Test
    fun `a fullwidth digit is not a digit of the number`() = row { changes ->
        commit("２")
        settle()
        assertEquals("5", fieldText())
        assertEquals(emptyList(), changes)
    }
}
