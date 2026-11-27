package hivens.history.api

import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import hivens.widget.model.ConfigId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HistoryTest {

    private val home = ConfigId("home")
    private val library = ConfigId("library")
    private val browse = ConfigId("browse")
    private val pack = ConfigId("pack")
    private val versions = ConfigId("pack.versions")

    private fun id(value: String): Map<String, JsonElement> = mapOf("pack.open" to JsonPrimitive(value))

    private fun History.show(config: ConfigId, values: Map<String, JsonElement> = emptyMap()) = show(Show(config, values))

    private fun History.section(config: ConfigId) = show(Show(config, over = home))

    private val History.trail get() = entries.map { it.config }

    @Test
    fun `a history starts at its root with nowhere to go`() {
        val history = History(home)
        assertEquals(home, history.current.config)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertSame(history, history.forward())
    }

    @Test
    fun `a history of nothing is refused`() {
        assertFailsWith<IllegalArgumentException> { History(emptyList()) }
    }

    @Test
    fun `what is shown goes on top, and back unwinds the way it was come`() {
        val deep = History(browse).show(pack, id("a")).show(versions, id("a"))
        assertEquals(HistoryEntry(versions, id("a")), deep.current)
        assertEquals(HistoryEntry(pack, id("a")), deep.back().current)
        assertEquals(History(browse).current, deep.back().back().current)
        assertFalse(deep.back().back().canGoBack)
    }

    @Test
    fun `the page shown asked for again moves nothing`() {
        val history = History(home).show(pack, id("a")).show(versions).back()
        assertSame(history, history.show(pack, id("a")), "the way forward is kept too")
        assertNotEquals(history, history.show(pack, id("b")), "another pack is another page")
    }

    @Test
    fun `a section goes over the config it names`() {
        val history = History(home).show(pack, id("a")).section(library)
        assertEquals(listOf(home, library), history.trail)
        assertEquals(home, history.back().current.config)
        assertEquals(listOf(home, browse), history.section(browse).trail, "sections do not pile up")
    }

    @Test
    fun `going to the config a section goes over leaves it alone in the history`() {
        val history = History(home).section(library).show(pack, id("a")).section(home)
        assertEquals(listOf(home), history.trail)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `the section shown already is cut back to, whatever stands between`() {
        val history = History(home).section(library).show(pack, id("a")).show(library)
        assertEquals(listOf(home, library), history.section(library).trail)
        assertEquals(listOf(home, library), history.section(library).section(library).trail)
    }

    @Test
    fun `a history without the config a section goes over becomes it and the section`() {
        assertEquals(listOf(home, library), History(browse).show(pack, id("a")).section(library).trail)
    }

    @Test
    fun `a section keeps what stands under the config it goes over`() {
        val start = ConfigId("welcome")
        assertEquals(listOf(start, home, library), History(start).show(home).show(browse).section(library).trail)
    }

    @Test
    fun `only the config opened with no values is gone over`() {
        val history = History(library).show(home, id("a")).section(browse)
        assertEquals(listOf(home, browse), history.trail)
        assertTrue(history.entries.first().opened.isEmpty())
    }

    @Test
    fun `a section drops the way forward, even back to where it is`() {
        val history = History(home).section(library).show(pack, id("a")).back()
        assertTrue(history.canGoForward)
        assertFalse(history.section(library).canGoForward)

        val root = History(home).section(library).back()
        assertTrue(root.canGoForward)
        assertFalse(root.section(home).canGoForward, "and back to the config it goes over")
    }

    @Test
    fun `a link to a section keeps the way back`() {
        val history = History(home).section(library).show(browse)
        assertEquals(listOf(home, library, browse), history.trail)
        assertEquals(library, history.back().current.config)
    }

    @Test
    fun `back at the root moves nothing and is counted`() {
        val history = History(home).section(library).back()
        assertEquals(0, history.backsAtRoot, "a back that went somewhere is not one at the root")
        val asked = history.back().back()
        assertEquals(2, asked.backsAtRoot)
        assertEquals(listOf(home), asked.trail)
        assertTrue(asked.canGoForward)
    }

    @Test
    fun `forward goes where back came from, in order`() {
        val history = History(home).show(library).show(pack, id("a")).back().back()
        assertEquals(library, history.forward().current.config)
        assertEquals(HistoryEntry(pack, id("a")), history.forward().forward().current)
        assertFalse(history.forward().forward().canGoForward)
    }

    @Test
    fun `going somewhere new drops the way forward`() {
        val history = History(home).show(library).back()
        assertTrue(history.canGoForward)
        assertFalse(history.show(browse).canGoForward)
    }

    @Test
    fun `back to a page lands on its nearest entry, and forward expands it again`() {
        val history = History(home).show(library).show(pack, id("a")).show(library).show(versions, id("a"))
        val there = history.backTo(HistoryEntry(library))
        assertEquals(listOf(home, library, pack, library), there.trail)
        assertEquals(listOf(home), history.backTo(HistoryEntry(home)).trail)
        assertEquals(history.trail, generateSequence(history.backTo(HistoryEntry(home))) { it.forward() }.take(5).last().trail)
    }

    @Test
    fun `back to the page shown or to one not behind moves nothing`() {
        val history = History(home).show(library)
        assertSame(history, history.backTo(HistoryEntry(library)))
        assertSame(history, history.backTo(HistoryEntry(browse)))
    }

    @Test
    fun `what back and forward reach is in the history, and what a section dropped is not`() {
        val history = History(home).show(library).show(pack, id("a")).back()
        assertEquals(setOf(home, library, pack), history.reachable.map { it.config }.toSet())
        assertEquals(setOf(home, browse), history.section(browse).reachable.map { it.config }.toSet())
    }

    @Test
    fun `a page rewrites its values and stays the page it was`() {
        val opened = History(home).show(pack, id("a"))
        val noted = opened.rewrite(mapOf("pack.settings" to JsonPrimitive("Version")))
        assertEquals(opened.current.page, noted.current.page)
        assertEquals(id("a") + ("pack.settings" to JsonPrimitive("Version")), noted.current.values)

        val returned = noted.show(versions, id("a")).back()
        assertEquals(noted.current, returned.current, "back finds what the page noted")
        assertSame(returned, returned.show(pack, id("a")), "and the link that opened it still means it")
        assertEquals(opened, noted.rewrite(mapOf("pack.settings" to null)), "a null takes the value out")
        assertSame(noted, noted.rewrite(mapOf("pack.settings" to JsonPrimitive("Version"), "nothing" to null)))
    }

    @Test
    fun `rewriting keeps the way forward`() {
        val history = History(home).show(library).back().rewrite(mapOf("x" to JsonPrimitive(1)))
        assertTrue(history.canGoForward)
    }

    @Test
    fun `a page is told by its config and its values, in any order`() {
        fun nested(vararg order: String) = buildJsonObject { order.forEach { put(it, it) } }
        val one = HistoryEntry(pack, mapOf("a" to nested("x", "y"), "b" to buildJsonArray { add(nested("x", "y")) }))
        val other = HistoryEntry(pack, mapOf("b" to buildJsonArray { add(nested("y", "x")) }, "a" to nested("y", "x")))
        assertEquals(one.page, other.page)
        assertNotEquals(one.page, HistoryEntry(versions, one.values).page)
        assertNotEquals(one.page, HistoryEntry(pack, one.values - "a").page)
        assertEquals("pack", HistoryEntry(pack).page)
    }

    @Test
    fun `a place that stopped existing leaves the history both ways`() {
        val gone = ConfigId("gone")
        val kept = ConfigId("kept")
        val history = History(home).show(gone).show(library).show(kept).back().retain(home) { it.config != gone }
        assertEquals(listOf(home, library), history.trail)
        assertEquals(kept, history.forward().current.config)

        val ahead = History(home).show(library).show(gone).back().retain(home) { it.config != gone }
        assertFalse(ahead.canGoForward)
    }

    @Test
    fun `a history of nothing but what is gone starts over at the fallback`() {
        assertEquals(History(home), History(browse).retain(home) { it.config != browse })
    }

    @Test
    fun `the same page on both sides of what is gone becomes one`() {
        val gone = ConfigId("gone")
        val behind = History(home).show(library).show(gone).show(library).retain(home) { it.config != gone }
        assertEquals(listOf(home, library), behind.trail)

        val ahead = History(home).show(library).show(gone).show(library).back().back().retain(home) { it.config != gone }
        assertEquals(listOf(home, library), ahead.trail)
        assertFalse(ahead.canGoForward, "a forward that goes nowhere")
    }

    @Test
    fun `a history that has nothing to drop is the same one`() {
        val history = History(home).show(library)
        assertSame(history, history.retain(home) { true })
    }

    @Test
    fun `a history reads back as it was written`() {
        val history = History(home).show(pack, id("a")).rewrite(mapOf("pack.settings" to JsonPrimitive("Version"))).show(library).back().back().back()
        assertEquals(history, Json.decodeFromString(History.serializer(), Json.encodeToString(History.serializer(), history)))
    }
}
