package hivens.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.auth.AuthProviderRegistry
import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.core.net.TransferEngine
import hivens.launcher.PackInstallService
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.background.BackgroundSettings
import hivens.ui.editor.EditorSurfaces
import hivens.ui.feature.catalogue.browse.BrowseSession
import hivens.ui.feature.catalogue.browse.BrowseTags
import hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.ThemeLibrary
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.FlowSpec
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a surface says its page hands its widgets ([hivens.ui.editor.EditorSurfaceSpec.provides])
 * is what the screen drawing that surface actually hands them: a probe widget needing
 * every listed key is put in each of the surface's slots, the screen is drawn in a
 * page of its own, and the probe has to draw rather than be found misplaced.
 *
 * The project page is not drawn here: its screen pulls the install graph out of Koin,
 * which a probe has no honest stand-in for. Its key is held by the drift test and by
 * the mod page's render sheets, which draw its widgets through the key.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenProvidesTest {

    private val data = uiDataRegistry(
        activity         = MutableStateFlow(emptyList()),
        notifications    = MutableStateFlow(emptyList()),
        doNotDisturb     = MutableStateFlow(false),
        sslBypass        = flowSource(MutableStateFlow(false)),
        background       = MutableStateFlow(BackgroundSettings()),
        accountsRevision = MutableStateFlow(0),
    )

    private val commands = uiCommandRegistry({}, {}, {}, {})

    /** An app level whose Koin also holds [services], which is where a screen drawn below it injects from. */
    private fun app(services: Module): WidgetScope = koinApplication {
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } }, services)
    }.koin.get()

    private class Probe(override val needs: List<String>, val drew: MutableList<String>) : WidgetDescriptor {
        override val kind = WidgetKind("test.probe")
        override val displayName = "Probe"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            drew += instance.instanceId
        }
    }

    /** Draws [screen] for [surface] with a probe in every slot, and says what the probes drew and what they missed. */
    private fun probe(surface: String, services: Module = module { }, screen: @Composable () -> Unit): Pair<List<String>, Set<String>> {
        val app = app(services)
        val id = SurfaceId(surface)
        val provides = EditorSurfaces.spec(id)?.provides.orEmpty().toList()
        val drew = ArrayList<String>()
        val missed = HashSet<String>()
        val descriptor = Probe(provides, drew)
        val registry = object : WidgetRegistry {
            override fun all() = mapOf(descriptor.kind to descriptor)
            override fun get(kind: WidgetKind) = descriptor.takeIf { it.kind == kind }
        }
        val bundled = DefaultLayout.load()
        val slots = bundled.surfaces[id]?.slotsOf(FamilyId.GENERAL)?.keys.orEmpty()
        val graph = bundled.copy(
            surfaces = bundled.surfaces + (
                id to SurfaceLayout(
                    slots = slots.associateWith {
                        SlotContent(widgets = listOf(WidgetInstance(kind = descriptor.kind, instanceId = "probe-${it.value}")), flow = FlowSpec.Column)
                    },
                )
            ),
        )
        onEventThread {
            val scene = ImageComposeScene(1200, 800, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalLayoutGraph provides graph,
                            LocalWidgetRegistry provides registry,
                            LocalSurfaceFamilies provides SurfaceFamilies(),
                            LocalMisplacedWidget provides { _, _, missing -> missed += missing },
                        ) {
                            ChildLevel(ScopeLevel.Composition) {
                                ChildLevel(ScopeLevel.Page) { Box(Modifier.fillMaxSize()) { screen() } }
                            }
                        }
                    }
                }
            }
            try {
                scene.settle(6)
            } finally {
                scene.close()
            }
        }
        assertTrue(slots.isNotEmpty(), "$surface has no slots in the bundled layout, so nothing was probed")
        return drew to missed
    }

    @Test
    fun `the theme picker hands its widgets what its surface lists`() {
        val (drew, missed) = probe("theme.picker") { ThemePickerSurface(library = ThemeLibrary()) }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `the about page hands its widgets what its surface lists`() {
        // The page asks the application's Koin for the host's actions, which an empty
        // one answers with none: no update check runs.
        startKoin { modules(module { }) }
        try {
            val (drew, missed) = probe("about") { AboutSurface(onBack = {}) }
            assertEquals(emptySet(), missed)
            assertTrue(drew.isNotEmpty())
        } finally {
            stopKoin()
        }
    }

    private class Listing(override val origin: PackOrigin) : IPackCatalogueService {
        override suspend fun search(query: String, page: Int): List<CataloguePack> = emptyList()
        override suspend fun details(packId: String): CataloguePackDetails = error("a probe reads no pack")
        override suspend fun versions(packId: String): List<CataloguePackVersion> = emptyList()
    }

    @Test
    fun `a catalogue pack's page hands its widgets what its surface lists`() {
        val http = HttpClientProvider { error("a probe makes no requests") }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val services = module {
            single { PackCatalogueRegistry(listOf(Listing(PackOrigin.Mirror))) }
            single { PackInstallService({ _, _, _, _ -> error("a probe installs nothing") }, scope) }
            single { BrowseSession() }
            single { OpenProjectState() }
            single { BrowseTags(ModrinthClient(http, TransferEngine(http)), scope, BrowseTags.Tags(emptyList(), emptyList(), emptyList())) }
            single { AuthProviderRegistry(emptyList()) }
        }
        val (drew, missed) = probe("catalogue.pack", services) {
            CataloguePackDetailScreen(origin = PackOrigin.Mirror, packId = "probe", onBack = {}, onInstalled = {})
        }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }
}
