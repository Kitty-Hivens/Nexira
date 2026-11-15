package hivens.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.ProjectTarget
import hivens.ui.background.BackgroundSettings
import hivens.ui.editor.EditorSurfaces
import hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen
import hivens.ui.feature.catalogue.project.ModDetailScreen
import hivens.ui.host.HostKoin
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
import hivens.widget.api.ProvideScoped
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.KoinApplication
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
 * The catalogue's pages are drawn with no catalogue module behind them: what they
 * hand their widgets is the screen's own, so it is there whether or not the module
 * answers the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenProvidesTest {

    private val data = uiDataRegistry(
        activity         = MutableStateFlow(emptyList()),
        notifications    = MutableStateFlow(emptyList()),
        doNotDisturb     = MutableStateFlow(false),
        sslBypass        = flowSource(MutableStateFlow(false)),
        background       = MutableStateFlow(BackgroundSettings()),
    )

    private val commands = uiCommandRegistry({}, {}, {})

    /** The Koin of an app level that also holds [services], which is where a screen drawn below it injects from. */
    private fun graph(services: Module): KoinApplication = koinApplication {
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } }, services)
    }

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
        val koin = graph(services)
        val app: WidgetScope = koin.koin.get()
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
                HostKoin(koin) {
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
            }
            try {
                scene.settle(6)
            } finally {
                scene.close()
                koin.close()
            }
        }
        assertTrue(slots.isNotEmpty(), "$surface has no slots in the bundled layout, so nothing was probed")
        return drew to missed
    }

    @Test
    fun `the theme picker hands its widgets what its surface lists`() {
        // The library is put over the composition rather than read off an appearance
        // store, which this probe's Koin does not hold.
        val (drew, missed) = probe("theme.picker") { ProvideScoped(Sources.ThemeLibrary, ThemeLibrary()) { ThemePickerSurface() } }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `the about page hands its widgets what its surface lists`() {
        // The page asks the application's Koin for the host's actions, which this
        // probe's answers with none: no update check runs.
        val (drew, missed) = probe("about") { AboutSurface(onBack = {}) }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `a catalogue pack's page hands its widgets what its surface lists`() {
        val (drew, missed) = probe("catalogue.pack") {
            CataloguePackDetailScreen(origin = PackOrigins.MIRROR, packId = "probe", onBack = {}, onInstalled = {})
        }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `a project's page hands its widgets what its surface lists`() {
        val (drew, missed) = probe("project") {
            ModDetailScreen(target = ProjectTarget.Catalogue("probe"), onOpenVersion = { _, _ -> })
        }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }
}
