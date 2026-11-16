package hivens.ui.widgets.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.WidgetStateHost
import hivens.widget.api.decodeWidgetState
import hivens.widget.api.encodeWidgetState
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notes and checklist widgets typed into: what is typed is what the widget's store
 * keeps, a line at a time in the notes and an item per Enter in the checklist.
 */
class NotesTypingTest {

    /** The widget state store, kept in memory. */
    private class Store(initial: Map<String, JsonObject> = emptyMap()) : WidgetStateHost {
        val kept = initial.toMutableMap()
        override fun load(instanceId: String): JsonObject? = kept[instanceId]
        override fun store(instanceId: String, value: JsonObject) {
            kept[instanceId] = value
        }
    }

    private fun widget(store: Store, content: @Composable () -> Unit, body: TypingScene.() -> Unit) = onEventThread {
        TypingScene(400, 400) {
            ShippedLanguageProvider("en") {
                NxTheme(Themes.default, dark = true) {
                    CompositionLocalProvider(LocalWidgetStateHost provides store) { content() }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.focusField()
            scene.body()
        }
    }

    private val notes = WidgetInstance(WidgetKind("notes.scratch"), "notes-1")
    private val checklist = WidgetInstance(WidgetKind("checklist"), "checklist-1")

    private fun Store.body(): String? = decodeWidgetState<NotesState>(kept[notes.instanceId])?.body
    private fun Store.items(): List<String> = decodeWidgetState<ChecklistState>(kept[checklist.instanceId])?.items.orEmpty().map { it.text }

    @Test
    fun `what is typed into a note is what the note keeps`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            commit("buy ")
            commit("bread")
            settle()
            assertEquals("buy bread", store.body())
            assertEquals("buy bread", fieldText())
        }
    }

    @Test
    fun `Enter in a note starts a new line`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            commit("one")
            press(Key.Enter)
            commit("two")
            settle()
            assertEquals("one\ntwo", store.body())
        }
    }

    @Test
    fun `a word an input method holds open in a note is kept once it is committed`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            compose("にほん")
            settle()
            assertTrue(composing, "the open word stays open while the note follows it")
            commit("日本")
            settle()
            assertEquals("日本", store.body())
            assertEquals("日本", fieldText())
        }
    }

    @Test
    fun `a note kept from before is in the field when the widget opens`() {
        val store = Store(mapOf(notes.instanceId to encodeWidgetState(NotesState(body = "from yesterday"))))
        widget(store, { NotesWidget(notes) }) {
            assertEquals("from yesterday", fieldText())
            assertEquals("from yesterday", store.body())
        }
    }

    @Test
    fun `Enter in the checklist adds the item and empties the field`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            commit("milk")
            press(Key.Enter)
            settle()
            assertEquals(listOf("milk"), store.items())
            assertEquals("", fieldText())
            commit("eggs")
            settle()
            press(Key.Enter)
            settle()
            assertEquals(listOf("milk", "eggs"), store.items())
        }
    }

    @Test
    fun `Enter on a blank checklist line adds nothing`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            commit("   ")
            settle()
            press(Key.Enter)
            settle()
            assertEquals(emptyList(), store.items())
        }
    }

    @Test
    fun `a checklist item from an input method is added whole on the Enter right behind it`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            compose("ぎゅう")
            settle()
            commit("牛乳")
            press(Key.Enter)
            settle()
            assertEquals(listOf("牛乳"), store.items())
            assertEquals("", fieldText())
        }
    }

    @Test
    fun `a letter typed at the next frame after an item and Enter starts the next item`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            commit("milk")
            press(Key.Enter)
            settle(1)
            assertEquals("", shown, "the field is empty at the first frame")
            commit("e")
            assertEquals("e", shown)
            settle()
            assertEquals(listOf("milk"), store.items())
            assertEquals("e", fieldText())
        }
    }
}
