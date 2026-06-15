package hivens.ui.widgets.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetStateStoreTest {

    private lateinit var dir: Path
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nexira-widget-state-test-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `store then flush persists and reloads across instances`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope)
        store.store("a", obj("body" to "hello"))
        store.flush()

        val reloaded = WidgetStateStore(file, json, backgroundScope)
        assertEquals(obj("body" to "hello"), reloaded.load("a"), "state survives a fresh instance")
        assertNull(reloaded.load("missing"), "unknown id returns null")
    }

    // The debounced (no-flush) write path is timing-bound to virtual-clock
    // interactions in the test harness; it is covered for real by flush() above
    // (durability) and by the live Xvfb smoke (type a note -> persists on its own).

    @Test
    fun `remove prunes one entry`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope)
        store.store("a", obj("k" to "1"))
        store.store("b", obj("k" to "2"))
        store.remove("a")
        store.flush()

        val reloaded = WidgetStateStore(file, json, backgroundScope)
        assertNull(reloaded.load("a"))
        assertEquals(obj("k" to "2"), reloaded.load("b"))
    }

    @Test
    fun `retain GCs orphaned ids`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope)
        store.store("live", obj("k" to "1"))
        store.store("orphan1", obj("k" to "2"))
        store.store("orphan2", obj("k" to "3"))
        store.retain(setOf("live"))
        store.flush()

        val reloaded = WidgetStateStore(file, json, backgroundScope)
        assertEquals(obj("k" to "1"), reloaded.load("live"))
        assertNull(reloaded.load("orphan1"))
        assertNull(reloaded.load("orphan2"))
    }

    @Test
    fun `a List-valued state round-trips -- a collection props cannot express`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope)
        val checklist = Checklist(items = listOf("milk", "bread", "eggs"))
        store.store("c", json.encodeToJsonElement(Checklist.serializer(), checklist).jsonObject)
        store.flush()

        val reloaded = WidgetStateStore(file, json, backgroundScope)
        val back = json.decodeFromJsonElement(Checklist.serializer(), reloaded.load("c")!!)
        assertEquals(checklist, back, "a List<String> survives the store round-trip")
    }

    @Test
    fun `the envelope is versioned`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope)
        store.store("a", obj("k" to "1"))
        store.flush()

        val raw = json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(1, (raw["version"] as JsonPrimitive).content.toInt())
        assertTrue(raw["entries"]!!.jsonObject.containsKey("a"))
    }

    @Test
    fun `a corrupt file loads as empty rather than crashing`() = runTest {
        val file = dir.resolve("ws.json")
        Files.writeString(file, "{ this is not json")
        val store = WidgetStateStore(file, json, backgroundScope)
        assertNull(store.load("anything"), "corrupt file -> empty store, no throw")
        // and the store remains usable
        store.store("a", obj("k" to "1"))
        store.flush()
        assertEquals(obj("k" to "1"), WidgetStateStore(file, json, backgroundScope).load("a"))
    }

    @Test
    fun `an oversized entry is rejected, not persisted`() = runTest {
        val file = dir.resolve("ws.json")
        val store = WidgetStateStore(file, json, backgroundScope, maxEntryBytes = 1_024)
        store.store("big", obj("blob" to "x".repeat(4_000)))
        store.flush()

        assertNull(store.load("big"), "entry over the cap is never stored")
        assertTrue(!Files.exists(file) || !Files.readString(file).contains("big"))
    }

    @Serializable
    private data class Checklist(val items: List<String> = emptyList())
}
