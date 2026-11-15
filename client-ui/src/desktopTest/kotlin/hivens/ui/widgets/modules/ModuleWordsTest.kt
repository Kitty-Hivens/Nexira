package hivens.ui.widgets.modules

import hivens.i18n.I18nEngine
import hivens.widget.api.ModuleStrings
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the host hands the language module of the modules it carries, and when. */
class ModuleWordsTest {

    private object NoWidgets : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }

    private fun snapshot(words: Map<String, ModuleStrings>) = WidgetModules.Snapshot(NoWidgets, emptyList(), words = words)

    private val module = ModuleStrings(
        tables = mapOf("en" to mapOf("hello" to "Hello from the module")),
        namespaces = mapOf("nexira" to mapOf("en" to mapOf("navBack" to "Back, says the module"))),
    )

    @Test
    fun `a module that leaves takes its words with it, those for other namespaces among them`() {
        val engine = I18nEngine(null, "en").apply { contribute("nexira", mapOf("nexira" to mapOf("en" to mapOf("navBack" to "Back")))) }
        val modules = MutableStateFlow(snapshot(mapOf("mod" to module)))
        followModuleWords(modules, engine, CoroutineScope(Dispatchers.Unconfined))
        assertEquals("Hello from the module", engine.strings("mod").text("hello"))
        assertEquals("Back, says the module", engine.strings("nexira").text("navBack"))

        modules.value = snapshot(emptyMap())
        assertEquals("hello", engine.strings("mod").text("hello"))
        assertEquals("Back", engine.strings("nexira").text("navBack"))
    }

    @Test
    fun `a module's plain files win over the files it wrote under its own namespace by name`() {
        val strings = ModuleStrings(
            tables = mapOf("en" to mapOf("hello" to "plain")),
            namespaces = mapOf("mod" to mapOf("en" to mapOf("hello" to "named", "only" to "only named"))),
        )
        assertEquals(mapOf("hello" to "plain", "only" to "only named"), strings.wordsOf("mod").getValue("mod").getValue("en"))
    }
}
