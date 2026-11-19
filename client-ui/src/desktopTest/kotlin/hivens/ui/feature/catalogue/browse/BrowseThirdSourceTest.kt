package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceFilterOption
import hivens.catalogue.api.SourceIcon
import hivens.catalogue.api.SourceOption
import hivens.catalogue.api.SourceView
import hivens.i18n.api.strings
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import hivens.themes.Themes
import hivens.ui.icons.NxIcon
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.theme.NxThemed
import hivens.widget.api.CompiledModule
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A source a third module brings, the way a jar would: Browse offers it beside the
 * mirror under the name its module gives it, and draws the filter it declares in its
 * module's words, with nothing in the launcher naming it.
 */
@OptIn(HostApi::class, ExperimentalComposeUiApi::class)
class BrowseThirdSourceTest {

    /** The third module: its words, and nothing else the launcher has to know of it. */
    private object ShelfModule : CompiledModule {
        override val id: String = "test.shelf"
        override val name: String = "Shelf"
        override val strings: ModuleStrings = ModuleStrings(
            mapOf("en" to mapOf("shelf.name" to "The Shelf", "shelf.era" to "Era", "shelf.dusty" to "Dusty", "shelf.new" to "Brand new")),
        )
        override val contributions: List<ModuleContribution> = emptyList()
    }

    private val shelf = SourceView(
        id = "Shelf",
        module = ShelfModule.id,
        label = "shelf.name",
        icon = SourceIcon.Glyph(NxIcon.Inventory2.codepoint),
        filters = listOf(
            SourceFilter("era", "shelf.era", listOf(SourceFilterOption("dusty", "shelf.dusty"), SourceFilterOption("new", "shelf.new"))),
        ),
    )

    private val mirror = SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label, MirrorPackSource.icon, MirrorPackSource.filters)

    private object NoServices : LevelServicesHost {
        override val services = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    private val browse = MutableStateFlow(BrowseView(origin = mirror.id, sources = listOf(mirror, shelf)))

    private val sources = WidgetDataRegistry().apply { registerFactory(CatalogueKeys.Browse) { flowSource(browse) } }

    private val commands = WidgetCommandRegistry().apply {
        registerFactory(CatalogueKeys.SetOrigin) { command { o -> browse.update { it.copy(pickedOrigin = o, origin = o) } } }
        registerFactory(CatalogueKeys.SetKind) { command { k -> browse.update { it.copy(kind = k) } } }
        registerFactory(CatalogueKeys.ToggleSourceOption) { command { o -> browse.update { it.copy(hidden = if (o in it.hidden) it.hidden - o else it.hidden + o) } } }
    }

    private val window: WidgetScope =
        WidgetScope.app(sources, ContributedServices().host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands).child(ScopeLevel.Window)

    private val scenes = ArrayList<ImageComposeScene>()

    @AfterTest
    fun tearDown() = scenes.forEach { it.close() }

    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(400, 900, density = Density(1f)) {
        CompositionLocalProvider(
            LocalWidgetDataRegistry provides sources,
            LocalWidgetCommandRegistry provides commands,
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalScope provides window,
        ) {
            ModuleLanguageProvider("en", listOf(MirrorSourceModule, ShelfModule)) { NxThemed(Themes.default, dark = true) { content() } }
        }
    }.also { scenes += it }

    private fun ImageComposeScene.frames(n: Int = 2) = repeat(n) {
        Snapshot.sendApplyNotifications()
        render()
    }

    private fun click(id: String) = PuppetRegistry.click(id).getOrThrow()

    @Test
    fun `a third source is offered by its id and lists its packs once picked`() {
        val scope = scene { BrowseScopeWidget(WidgetInstance(WidgetKind("browse.scope"), "scope")) }
        scope.frames()
        click("browse.source.Shelf")
        scope.frames()
        assertEquals("Shelf", browse.value.origin)
        assertEquals(shelf, browse.value.source)
    }

    @Test
    fun `its own filter is drawn and turned the way the mirror's is, and the mirror's is gone`() {
        browse.update { it.copy(kind = null, pickedOrigin = shelf.id, origin = shelf.id) }
        val filters = scene { BrowseSourceFiltersWidget(WidgetInstance(WidgetKind("browse.filter.builtBy"), "filters")) }
        filters.frames()
        click("browse.filter.era.dusty")
        filters.frames()
        val dusty = SourceOption(shelf.id, "era", "dusty")
        assertFalse(browse.value.lists(dusty), "turned off")
        assertTrue(browse.value.lists(SourceOption(shelf.id, "era", "new")))
        assertTrue(PuppetRegistry.click("browse.filter.builtBy.own").isFailure, "the mirror's switch is not drawn for another source")

        val listed = CatalogueCard(shelf.id, "old", "Old", "", facets = mapOf("era" to "dusty"))
        assertFalse(browse.value.shows(listed), "its packs under the option are left out")
    }

    @Test
    fun `its name and its filter's words are its module's own`() {
        var named = ""
        var title = ""
        val words = scene {
            named = sourceLabel(shelf)
            title = strings(shelf.module).text(shelf.filters.single().label)
        }
        words.frames(1)
        assertEquals("The Shelf", named)
        assertEquals("Era", title)
    }
}
