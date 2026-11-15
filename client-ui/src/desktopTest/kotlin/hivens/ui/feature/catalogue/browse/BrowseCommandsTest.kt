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
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.ProjectKind
import hivens.ui.i18n.ShippedLanguageProvider
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

    private val browse = MutableStateFlow(BrowseView(origins = listOf(PackOrigins.MIRROR)))
    /** Who showed the results and who stopped, in order. */
    private val shown = ArrayList<String>()
    private val hidden = ArrayList<String>()

    private val sources = WidgetDataRegistry().apply {
        registerFactory(CatalogueKeys.Browse) { flowSource(browse) }
    }

    private val commands = WidgetCommandRegistry().apply {
        registerFactory(CatalogueKeys.ToggleHideInstalled) { command { browse.update { it.copy(hideInstalled = !it.hideInstalled) } } }
        registerFactory(CatalogueKeys.ToggleMirrorOwn) { command { browse.update { it.copy(mirrorOwn = !it.mirrorOwn) } } }
        registerFactory(CatalogueKeys.ToggleMirrorCommunity) { command { browse.update { it.copy(mirrorCommunity = !it.mirrorCommunity) } } }
        registerFactory(CatalogueKeys.SetHideInstalled) { command { hide -> browse.update { it.copy(hideInstalled = hide) } } }
        registerFactory(CatalogueKeys.SetMirrorOwn) { command { listed -> browse.update { it.copy(mirrorOwn = listed) } } }
        registerFactory(CatalogueKeys.SetMirrorCommunity) { command { listed -> browse.update { it.copy(mirrorCommunity = listed) } } }
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
            ShippedLanguageProvider("en") { NxTheme(dark = true) { content() } }
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
        browse.value = browse.value.copy(kind = null, origin = PackOrigins.MIRROR)
        val builtBy = scene { BrowseBuiltByFilterWidget(WidgetInstance(WidgetKind("browse.filter.builtBy"), "builtBy")) }
        try {
            builtBy.frames()
            click("browse.filter.builtBy.own")
            click("browse.filter.builtBy.own")
            click("browse.filter.builtBy.community")
        } finally {
            builtBy.close()
        }
        assertTrue(browse.value.mirrorOwn, "turned off and on again")
        assertFalse(browse.value.mirrorCommunity)

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
