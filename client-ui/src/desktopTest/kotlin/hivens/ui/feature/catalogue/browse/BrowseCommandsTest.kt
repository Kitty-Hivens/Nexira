package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.catalogue.api.BrowseTarget
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.SourceOption
import hivens.catalogue.api.SourceView
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import hivens.themes.Themes
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.theme.NxTheme
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Browse and its switches fire at the window's search, with the catalogue module
 * stood in for by the commands it answers.
 */
@OptIn(HostApi::class, ExperimentalComposeUiApi::class)
class BrowseCommandsTest {

    private object NoServices : LevelServicesHost {
        override val services = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    /** The mirror source as the catalogue lists it, its filter as the module declares it. */
    private val mirror = SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label, MirrorPackSource.icon, MirrorPackSource.filters)

    private val browse = MutableStateFlow(BrowseView(origin = mirror.id, sources = listOf(mirror)))
    /** Who showed the results and who stopped, in order. */
    private val shown = ArrayList<String>()
    private val hidden = ArrayList<String>()

    private val sources = WidgetDataRegistry().apply {
        registerFactory(CatalogueKeys.Browse) { flowSource(browse) }
    }

    private val commands = WidgetCommandRegistry().apply {
        registerFactory(CatalogueKeys.ToggleHideInstalled) { command { browse.update { it.copy(hideInstalled = !it.hideInstalled) } } }
        registerFactory(CatalogueKeys.ToggleSourceOption) { command { o -> browse.update { it.copy(hidden = if (o in it.hidden) it.hidden - o else it.hidden + o) } } }
        registerFactory(CatalogueKeys.SetHideInstalled) { command { hide -> browse.update { it.copy(hideInstalled = hide) } } }
        registerFactory(CatalogueKeys.SetSourceOption) { command { set -> browse.update { it.copy(hidden = if (set.listed) it.hidden - set.option else it.hidden + set.option) } } }
        registerFactory(CatalogueKeys.SetTarget) { command { _ -> } }
        registerFactory(CatalogueKeys.ShowResults) { command { viewer -> shown += viewer } }
        registerFactory(CatalogueKeys.HideResults) { command { viewer -> hidden += viewer } }
    }

    private val window: WidgetScope =
        WidgetScope.app(sources, ContributedServices().host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands).child(ScopeLevel.Window)

    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(400, 600, density = Density(1f)) {
        CompositionLocalProvider(
            LocalWidgetDataRegistry provides sources,
            LocalWidgetCommandRegistry provides commands,
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalScope provides window,
        ) {
            ModuleLanguageProvider("en", listOf(MirrorSourceModule)) { NxTheme(Themes.default, dark = true) { content() } }
        }
    }

    private fun ImageComposeScene.frames(n: Int = 2) = repeat(n) {
        Snapshot.sendApplyNotifications()
        render()
    }

    private fun click(id: String) = PuppetRegistry.click(id).getOrThrow()

    /** The view a widget reads arrives a frame late, so two clicks before the next frame read the same one. */
    @Test
    fun `two quick clicks on a switch are two turns, not the same one twice`() {
        browse.value = browse.value.copy(kind = null, origin = mirror.id)
        val builtBy = scene { BrowseSourceFiltersWidget(WidgetInstance(WidgetKind("browse.filter.builtBy"), "builtBy")) }
        try {
            builtBy.frames()
            click("browse.filter.builtBy.own")
            click("browse.filter.builtBy.own")
            click("browse.filter.builtBy.community")
        } finally {
            builtBy.close()
        }
        assertTrue(browse.value.lists(SourceOption(mirror.id, MirrorPackSource.BUILT_BY, MirrorPackSource.OWN)), "turned off and on again")
        assertFalse(browse.value.lists(SourceOption(mirror.id, MirrorPackSource.BUILT_BY, MirrorPackSource.COMMUNITY)))

        browse.value = browse.value.copy(
            kind = ProjectKind.Mod,
            targetId = "p",
            target = BrowseTarget("p", "Probe", "1.21.1", "fabric", listOf(ProjectKind.Mod)),
        )
        val installed = scene { BrowseInstalledFilterWidget(WidgetInstance(WidgetKind("browse.filter.installed"), "installed")) }
        try {
            installed.frames()
            click("browse.filter.installed")
            click("browse.filter.installed")
        } finally {
            installed.close()
        }
        assertFalse(browse.value.hideInstalled, "turned on and off again")
    }

    /** The search runs while Browse shows its results, so leaving it is what stops the search. */
    @Test
    fun `Browse shows the window's results for as long as it is up`() {
        var up by mutableStateOf(true)
        val screen = scene { if (up) BrowseScreen() }
        try {
            screen.frames()
            assertEquals(1, shown.size)
            assertEquals(emptyList(), hidden)
            screen.frames()
            assertEquals(1, shown.size, "a frame is not another showing")

            up = false
            screen.frames()
            assertEquals(shown, hidden, "leaving stops what it started")
            up = true
            screen.frames()
            assertEquals(2, shown.size)
            assertEquals(1, hidden.size)
        } finally {
            screen.close()
        }
    }
}
