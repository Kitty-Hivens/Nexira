package hivens.widget.api

import hivens.widget.model.ConfigId
import hivens.widget.model.HistoryEntry
import hivens.widget.model.PageValue
import hivens.widget.model.ScopeLevel
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

    private fun page(entry: MutableStateFlow<HistoryEntry>?): WidgetScope {
        val commands = WidgetCommandRegistry()
        val contribution = object : KeyContribution {
            override val id = "test.keys"
            override val label = "Test keys"
            override fun declare(keys: ModuleKeys) = keys.pageValue(title) { "sample" }
        }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("test", contribution, sources, commands, ContributedServices()))
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
}
