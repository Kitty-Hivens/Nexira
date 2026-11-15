package hivens.module.catalogueconsumer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module runs a search and reads what it found, typed, through nothing but the
 * kernel and the catalogue API. The test stands in for the launcher: it answers the
 * window's search the way the catalogue module would, then brings this module's keys in
 * the way the host brings a jar's, not trusted.
 */
@OptIn(HostApi::class, ExperimentalComposeUiApi::class)
class FoundTest {

    private object NoServices : LevelServicesHost {
        override val services = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    /** What the stand-in catalogue holds, by the words it is searched for. */
    private val catalogue = mapOf(
        "sodium" to listOf(ProjectHit(projectId = "AANobbMI", title = "Sodium"), ProjectHit(projectId = "gvQqBUqZ", title = "Lithium")),
    )

    private val results = MutableStateFlow(SearchResults())
    private val asked = mutableListOf<String>()

    /** The window's search as the catalogue module answers it, or none of it when [withCatalogue] is false. */
    private fun tree(withCatalogue: Boolean): Triple<WidgetScope, WidgetDataRegistry, WidgetCommandRegistry> {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        if (withCatalogue) {
            sources.registerFactory(CatalogueKeys.Results) { flowSource(results) }
            commands.registerFactory(CatalogueKeys.SetQuery) {
                command { words ->
                    asked += words
                    val found = catalogue[words].orEmpty()
                    results.value = SearchResults(
                        status = if (found.isEmpty()) SearchStatus.Empty else SearchStatus.Loaded,
                        projects = found,
                        endReached = true,
                    )
                }
            }
        }
        val services = ContributedServices()
        assertTrue(registerModuleKeys("catalogue-consumer", FoundKeys(), sources, commands, services, trusted = false) is ModuleKeysResult.Registered)
        val app = WidgetScope.app(sources, services.host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands)
        return Triple(app.child(ScopeLevel.Window), sources, commands)
    }

    @Test
    fun `its own key is made from the window's results, typed, and follows them`() {
        val (window) = tree(withCatalogue = true)
        assertEquals(emptyList(), window.current(FoundKeys.Titles), "nothing is found before anything is asked")
        assertNotNull(window.findCommand(CatalogueKeys.SetQuery)).run("sodium")
        assertEquals(listOf("Sodium", "Lithium"), window.current(FoundKeys.Titles))
        window.findCommand(CatalogueKeys.SetQuery)?.run("nothing like it")
        assertEquals(emptyList(), window.current(FoundKeys.Titles))
    }

    @Test
    fun `without the catalogue module its own key is missing too, and nothing throws`() {
        val (window) = tree(withCatalogue = false)
        assertNull(window.find(FoundKeys.Titles))
        assertNull(window.findCommand(CatalogueKeys.SetQuery))
    }

    @Test
    fun `a widget runs a search and reads what it found`() {
        val (window, sources, commands) = tree(withCatalogue = true)
        var read: SearchResults? = null
        var search: ((String) -> Unit)? = null
        fun draw() {
            val scene = ImageComposeScene(50, 50, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides sources,
                    LocalWidgetCommandRegistry provides commands,
                    LocalScope provides window,
                ) {
                    SearchBox { r, s ->
                        read = r
                        search = s
                    }
                }
            }
            try {
                scene.render()
            } finally {
                scene.close()
            }
        }
        draw()
        assertEquals(SearchStatus.Loading, read?.status, "the search is under way before it is asked anything")
        assertNotNull(search).invoke("sodium")
        assertEquals(listOf("sodium"), asked)
        draw()
        assertEquals(SearchStatus.Loaded, read?.status)
        assertEquals(listOf("AANobbMI", "gvQqBUqZ"), read?.projects?.map { it.projectId })
    }

    @Test
    fun `a widget where the catalogue module is not brought in reads nothing and has no search`() {
        val (window, sources, commands) = tree(withCatalogue = false)
        var found = true
        val scene = ImageComposeScene(50, 50, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides sources,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides window,
            ) {
                SearchBox { r, s -> found = r != null || s != null }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertTrue(!found, "it found catalogue keys nobody brought in")
    }
}
