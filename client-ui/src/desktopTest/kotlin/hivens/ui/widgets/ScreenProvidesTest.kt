package hivens.ui.widgets

import hivens.ui.feature.catalogue.project.ProjectPageSource
import hivens.ui.feature.catalogue.browse.CataloguePackPageSource
import hivens.ui.widgets.about.OpenUpdateDialog
import hivens.ui.widgets.about.AboutUpdateSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.catalogue.api.ProjectTarget
import hivens.themes.Themes
import hivens.themes.api.ThemesKeys
import hivens.ui.Standing
import hivens.ui.background.BackgroundSettings
import hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen
import hivens.ui.feature.catalogue.project.ModDetailScreen
import hivens.ui.host.HostKoin
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.FlowSpec
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
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
 * What a screen declares it hands the widgets in its slots (@Widget(hands = ...)) is what
 * it actually hands them: a probe widget needing every declared key is put in each of the
 * screen's slots, the screen is drawn in a page of its own, and the probe has to draw
 * rather than be found misplaced.
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

    /**
     * Draws [screen], the container [kind] that once drew [surface], with a probe in every slot it declares, and says
     * what the probes drew and what they missed.
     */
    private fun probe(
        surface: String,
        kind: String,
        services: Module = module { },
        screen: @Composable (WidgetInstance) -> Unit,
    ): Pair<List<String>, Set<String>> {
        val koin = graph(services)
        val app: WidgetScope = koin.koin.get()
        val provides = GeneratedWidgetRegistry[WidgetKind(kind)]?.hands.orEmpty()
        val drew = ArrayList<String>()
        val missed = HashSet<String>()
        val descriptor = Probe(provides, drew)
        val registry = object : WidgetRegistry {
            override fun all() = mapOf(descriptor.kind to descriptor)
            override fun get(kind: WidgetKind) = descriptor.takeIf { it.kind == kind }
        }
        val slots = GeneratedWidgetRegistry[WidgetKind(kind)]?.slots.orEmpty()
        val container = WidgetInstance(
            kind = WidgetKind(kind),
            instanceId = kind,
            children = slots.associateWith {
                SlotContent(widgets = listOf(WidgetInstance(kind = descriptor.kind, instanceId = "probe-${it.value}")), flow = FlowSpec.Column)
            },
        )
        onEventThread {
            val scene = ImageComposeScene(1200, 800, density = Density(1f)) {
                HostKoin(koin) {
                    ShippedLanguageProvider("en") {
                        NxThemed(Themes.default, dark = true) {
                            CompositionLocalProvider(
                                LocalWidgetDataRegistry provides data,
                                LocalWidgetCommandRegistry provides commands,
                                LocalScope provides app,
                                LocalWidgetRegistry provides registry,
                                LocalMisplacedWidget provides { _, _, missing -> missed += missing },
                            ) {
                                ChildLevel(ScopeLevel.Composition) {
                                    ChildLevel(ScopeLevel.Page) { Box(Modifier.fillMaxSize()) { Standing(container, screen) } }
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
        assertTrue(slots.isNotEmpty(), "$kind declares no slots, so nothing was probed")
        return drew to missed
    }

    @Test
    fun `the theme picker hands its widgets what it declares`() {
        // The themes on offer and the one in use are the app's values here rather than the
        // themes module's answers, since this probe's Koin holds no manager to answer them.
        data.register(ThemesKeys.Offered, flowSource(MutableStateFlow(Themes.all)))
        data.register(ThemesKeys.Active, flowSource(MutableStateFlow(Themes.default)))
        val (drew, missed) = probe("theme.picker", "screen.theme.picker") { ThemePickerSurface(it) }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `the about page hands its widgets what it declares`() {
        // The page asks the application's Koin for the host's actions, which this
        // probe's answers with none: no update check runs.
        val (drew, missed) = probe("about", "screen.about") { AboutSurface(it, onBack = {}) }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `a catalogue pack's page hands its widgets what it declares`() {
        val (drew, missed) = probe("catalogue.pack", "screen.catalogue.pack") {
            CataloguePackDetailScreen(it, origin = "Mirror", packId = "probe", onBack = {}, onInstalled = {})
        }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `a project's page hands its widgets what it declares`() {
        val (drew, missed) = probe("project", "screen.project") {
            ModDetailScreen(it, target = ProjectTarget.Catalogue("probe"), onOpenVersion = { _, _ -> })
        }
        assertEquals(emptySet(), missed)
        assertTrue(drew.isNotEmpty())
    }

    @Test
    fun `each screen declares the keys it hands, under the ids its keys have`() {
        fun hands(kind: String) = GeneratedWidgetRegistry[WidgetKind(kind)]?.hands.orEmpty()
        assertEquals(listOf(AboutUpdateSource.id, OpenUpdateDialog.id), hands("screen.about"))
        assertEquals(listOf(CataloguePackPageSource.id), hands("screen.catalogue.pack"))
        assertEquals(listOf(ProjectPageSource.id), hands("screen.project"))
        assertEquals(emptyList(), hands("screen.theme.picker"))
    }
}
