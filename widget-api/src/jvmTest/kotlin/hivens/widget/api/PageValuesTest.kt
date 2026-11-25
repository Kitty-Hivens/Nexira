package hivens.widget.api

import hivens.widget.model.ConfigId
import hivens.widget.model.HistoryEntry
import hivens.widget.model.PageValue
import hivens.widget.model.ScopeLevel
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What an opening hands a page, as the widgets of the page and the factories of its level read it. */
class PageValuesTest {

    private val title = PageValue("page.name", String.serializer())

    private val sources = WidgetDataRegistry()

    private fun registerTitle(commands: WidgetCommandRegistry = WidgetCommandRegistry()) {
        val contribution = object : KeyContribution {
            override val id = "test.keys"
            override val label = "Test keys"
            override fun declare(keys: ModuleKeys) = keys.pageValue(title) { "sample" }
        }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("test", contribution, sources, commands, ContributedServices()))
    }

    private fun page(entry: MutableStateFlow<HistoryEntry>?): WidgetScope {
        val commands = WidgetCommandRegistry()
        registerTitle(commands)
        val composition = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Unconfined, commands).child(ScopeLevel.Composition)
        return composition.pages.levelFor("page") { if (entry != null) provideSource(PageKeys.Entry, flowSource(entry)) }
    }

    private fun opened(value: String) = HistoryEntry(ConfigId("project"), mapOf(title.of(value)))

    @Test
    fun `a page reads what it was opened with, and follows it`() {
        val entry = MutableStateFlow(opened("Sodium"))
        val page = page(entry)
        assertEquals("Sodium", page.current(title.key))
        entry.value = opened("Lithium")
        assertEquals("Lithium", page.current(title.key))
        entry.value = HistoryEntry(ConfigId("project"))
        assertNull(page.current(title.key))
    }

    @Test
    fun `a page drawn for no entry reads nothing and is not broken`() {
        val page = page(entry = null)
        assertNull(page.find(title.key)?.state?.value)
        assertEquals(true, page.answers(KeyDeclaration(title.id, ScopeLevel.Page, KeyKind.Source, KeyOrigin.Factory)))
    }

    @Test
    fun `the value stops following when its page goes`() {
        val entry = MutableStateFlow(opened("Sodium"))
        val page = page(entry)
        val source = requireNotNull(page.find(title.key)).state
        page.close()
        entry.value = opened("Lithium")
        assertEquals("Sodium", source.value)
        assertEquals(0, entry.subscriptionCount.value)
    }

    @Test
    fun `the palette has its sample`() {
        page(entry = null)
        assertEquals("sample", sources.sampleOf(title.id)?.invoke())
    }

    @Test
    fun `a page level answers the entry it was last asked for with, and reads its values at once`() {
        registerTitle()
        val pages = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Default).child(ScopeLevel.Composition).pages
        val entry = opened("Sodium")
        val level = pages.levelFor(entry)
        assertEquals(entry, level.current(PageKeys.Entry))
        assertEquals("Sodium", level.current(title.key))

        val noted = entry.copy(values = mapOf(title.of("Lithium")))
        assertSame(level, pages.levelFor(noted), "the page it was opened as is the page it stays")
        assertEquals(noted, level.current(PageKeys.Entry))
        assertEquals("Lithium", level.current(title.key), "on the thread that moved it, with nothing waited for")
        assertNotSame(level, pages.levelFor(opened("Lithium")))
    }

    @Test
    fun `a page that left and is opened again answers its new entry`() {
        registerTitle()
        val pages = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Default).child(ScopeLevel.Composition).pages
        val entry = opened("Sodium")
        val first = pages.levelFor(entry.copy(values = mapOf(title.of("noted"))))
        pages.keep(emptySet())
        assertTrue(first.closed)
        val again = pages.levelFor(entry)
        assertNotSame(first, again)
        assertEquals("Sodium", again.current(title.key))
    }
}
