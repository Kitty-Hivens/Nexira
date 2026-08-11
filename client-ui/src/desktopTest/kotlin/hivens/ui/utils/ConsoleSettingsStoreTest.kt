package hivens.ui.utils

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Console preferences are read by three surfaces at once -- Settings > Console,
 * the standalone window and the pack's Logs tab -- and each used to hold a copy
 * of the file. The shell's copy was taken at startup and written back whole on
 * any edit, so a highlight rule added in Settings was gone the next time a switch
 * in the console's gear was flipped.
 *
 * What these pin is the store being the single owner: one value, published, and
 * every write starting from what is current.
 */
class ConsoleSettingsStoreTest {

    private val dir: Path = Files.createTempDirectory("console-settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    /** Writes land on the test's own scheduler, so a test says when they happen. */
    private val scheduler = StandardTestDispatcher()
    private val scope = TestScope(scheduler)

    private fun store() = ConsoleSettingsStore(dir, json, scope, writeDispatcher = scheduler)

    /** Let the debounced write run. */
    private fun settle() = scope.advanceUntilIdle()

    @Test
    fun `a store with no file on disk starts at the defaults`() {
        assertEquals(ConsoleSettings(), store().current)
    }

    @Test
    fun `an edit is published at once and persisted behind it`() {
        val store = store()
        store.update(store.current.copy(fontSize = 16))

        assertEquals(16, store.settings.value.fontSize, "the value every surface renders from")
        settle()
        assertEquals(16, store().current.fontSize, "and what the next process reads")
    }

    @Test
    fun `an edit from one surface is what the next edit starts from`() {
        // The clobber, in miniature: Settings adds a rule, the console's gear
        // flips a switch. Both go through the store, so the second write carries
        // the first instead of the copy it was handed when it opened.
        val store = store()
        store.update(store.current.copy(highlightRules = listOf(HighlightRule(pattern = "OutOfMemory"))))
        store.update(store.current.copy(wrapText = false))
        settle()

        val saved = store().current
        assertEquals(listOf(HighlightRule(pattern = "OutOfMemory")), saved.highlightRules)
        assertTrue(!saved.wrapText)
    }

    @Test
    fun `a drag does not reach the disk until it settles`() {
        // The sliders report continuously; every published value is live, and the
        // file is untouched until the drag stops -- on a fresh directory, "not
        // written yet" is the file not being there at all.
        val store = store()
        (8..16).forEach { store.update(store.current.copy(fontSize = it)) }

        assertEquals(16, store.current.fontSize, "published as it is dragged")
        assertFalse(Files.exists(dir.resolve("console.json")), "and not written per reported value")

        settle()
        assertEquals(16, store().current.fontSize)
    }

    @Test
    fun `a quit before the debounce lands still writes the edit`() {
        val store = store()
        store.update(store.current.copy(showTimestamps = false))

        store.flush()

        assertEquals(false, store().current.showTimestamps)
    }

    @Test
    fun `bounded knobs are clamped on the way in`() {
        val store = store()
        store.update(store.current.copy(fontSize = 400, maxInMemoryLines = 10))

        assertEquals(ConsoleSettings.MAX_FONT_SIZE, store.current.fontSize)
        assertEquals(ConsoleSettings.MIN_IN_MEMORY_LINES, store.current.maxInMemoryLines)
    }

    @Test
    fun `a malformed file reads as the defaults rather than taking the console down`() {
        Files.writeString(dir.resolve("console.json"), "{ not json")

        assertEquals(ConsoleSettings(), store().current)
    }
}
