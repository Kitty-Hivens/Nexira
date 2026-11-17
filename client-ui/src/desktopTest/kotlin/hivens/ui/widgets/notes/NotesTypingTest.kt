package hivens.ui.widgets.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.theme.NxThemed
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
                NxThemed(Themes.default, dark = true) {
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

    /** The note shows [expected] at the next frame, and the store keeps it once the write has gone out. */
    private fun TypingScene.keeps(store: Store, expected: String, what: String) {
        settle(1)
        assertEquals(expected, shown, "the note after $what")
        settle()
        assertEquals(expected, fieldText(), "the note once settled after $what")
        assertEquals(expected, store.body(), "what the note keeps after $what")
    }

    @Test
    fun `a note cut with Ctrl and X comes back with Ctrl and Z`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            commit("buy bread")
            settle()
            press(Key.A, shortcut = true)
            pressToClipboard(Key.X)
            assertEquals("buy bread", clipboard.text)
            keeps(store, "", "a cut")
            press(Key.Z, shortcut = true)
            keeps(store, "buy bread", "an undo of the cut")
        }
    }

    @Test
    fun `a paste over a word in a note, its undo and its redo`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            clipboard.text = "milk"
            commit("buy bread")
            settle()
            press(Key.DirectionLeft, word = true, shift = true)
            settle(1)
            assertEquals("bread", selected)
            paste()
            keeps(store, "buy milk", "a paste over the word")
            press(Key.Z, shortcut = true)
            keeps(store, "buy bread", "an undo of the paste")
            press(Key.Y, shortcut = true)
            keeps(store, "buy milk", "a redo of the paste")
        }
    }

    @Test
    fun `Ctrl and C copies from a note and leaves it as it is`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            clipboard.text = "from before"
            commit("call home")
            settle()
            press(Key.A, shortcut = true)
            pressToClipboard(Key.C)
            assertEquals("call home", clipboard.text)
            keeps(store, "call home", "a copy")
        }
    }

    // Home and End on Windows and Linux, the command key with Left and Right on macOS, where Home
    // and End go to the ends of the whole note.
    @Test
    fun `the line's start and end keys in a note go to the ends of the caret's line`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            commit("one")
            press(Key.Enter)
            commit("two")
            settle()
            lineStart()
            commit("- ")
            keeps(store, "one\n- two", "typing at the start of the line")
            lineEnd()
            commit("!")
            keeps(store, "one\n- two!", "typing at the end of the line")
            lineStart(select = true)
            settle(1)
            assertEquals("- two!", selected)
            press(Key.DirectionUp)
            lineStart()
            lineEnd(select = true)
            settle(1)
            assertEquals("one", selected, "the selection stops at the end of the first line")
        }
    }

    @Test
    fun `Ctrl and Backspace and Ctrl and Delete take words out of a note`() {
        val store = Store()
        widget(store, { NotesWidget(notes) }) {
            commit("buy fresh bread")
            settle()
            press(Key.Backspace, word = true)
            keeps(store, "buy fresh ", "Ctrl and Backspace")
            press(Key.MoveHome)
            press(Key.Delete, word = true)
            keeps(store, " fresh ", "Ctrl and Delete")
            press(Key.Z, shortcut = true)
            keeps(store, "buy fresh ", "an undo of Ctrl and Delete")
        }
    }

    /** The add field shows [expected] at the next frame, and Enter right then adds it as the newest item. */
    private fun TypingScene.adds(store: Store, expected: String, what: String) {
        settle(1)
        assertEquals(expected, shown, "the add field after $what")
        press(Key.Enter)
        settle()
        assertEquals(expected, store.items().last(), "the item Enter added after $what")
        assertEquals("", fieldText())
    }

    @Test
    fun `a checklist item pasted over a selection is the item Enter adds`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            clipboard.text = "milk"
            commit("bread")
            settle()
            press(Key.A, shortcut = true)
            paste()
            adds(store, "milk", "a paste over the selection")
        }
    }

    @Test
    fun `Ctrl and Z after a paste into a checklist item takes the paste back`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            clipboard.text = " x6"
            commit("eggs")
            settle()
            paste()
            settle(1)
            assertEquals("eggs x6", shown)
            press(Key.Z, shortcut = true)
            adds(store, "eggs", "an undo of the paste")
        }
    }

    @Test
    fun `a checklist item cut away adds nothing and comes back with Ctrl and Z`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            commit("tea")
            settle()
            press(Key.A, shortcut = true)
            pressToClipboard(Key.X)
            assertEquals("tea", clipboard.text)
            settle(1)
            assertEquals("", shown)
            press(Key.Enter)
            settle()
            assertEquals(emptyList(), store.items(), "the widget holds the empty line too")
            press(Key.Z, shortcut = true)
            adds(store, "tea", "an undo of the cut")
        }
    }

    @Test
    fun `Home, Shift and End and Ctrl and Backspace in a checklist item`() {
        val store = Store()
        widget(store, { ChecklistWidget(checklist) }) {
            commit("buy oat milk")
            settle()
            press(Key.MoveHome)
            press(Key.MoveEnd, shift = true)
            settle(1)
            assertEquals("buy oat milk", selected)
            commit("oat milk tea")
            settle(1)
            assertEquals("oat milk tea", shown)
            press(Key.Backspace, word = true)
            settle(1)
            assertEquals("oat milk ", shown)
            commit("latte")
            adds(store, "oat milk latte", "Ctrl and Backspace and typing")
        }
    }
}
