// The test plays the host: it makes the app level and brings in the controls module's keys.
@file:OptIn(HostApi::class)

package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.data.SettingsData
import hivens.i18n.ShippedLanguages
import hivens.look.colour.nx.controls.ColourNxControls
import hivens.themes.Themes
import hivens.ui.host.HostKoin
import hivens.ui.i18n.NexiraStrings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.bgsettings.AppearanceThemeIsland
import hivens.widget.api.CompositeWidgetRegistry
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.DefaultLayout
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The host screens whose slots the colour provider's controls fill, with colour not drawn by nx: the
 * settings Appearance section and the Appearance studio's theme island take exactly the room they
 * would take if those slots were not in them at all.
 *
 * "Not there" is measured, not drawn by hand: the same screen with a block of [PROBE] in each of the
 * slots is taller by the blocks and one gap of the screen's own for each, no more and no less. A slot
 * whose controls are left out then has to give back its gap as well, which a column spacing them out
 * would not.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ControllerSlotsAbsenceTest {

    private val koin = koinApplication { }

    @AfterTest fun tearDown() = koin.close()

    /** A block of [PROBE] across the width, standing in for what a slot could hold. */
    private object Probe : WidgetDescriptor {
        override val kind = WidgetKind("test.probe")
        override val displayName = "Probe"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Box(Modifier.fillMaxWidth().height(PROBE.dp))
    }

    private val registry: WidgetRegistry = CompositeWidgetRegistry(
        listOf(
            ColourNxControls.widgets,
            object : WidgetRegistry {
                override fun all(): Map<WidgetKind, WidgetDescriptor> = mapOf(Probe.kind to Probe)
                override fun get(kind: WidgetKind): WidgetDescriptor? = Probe.takeIf { it.kind == kind }
            },
        ),
    )

    // The controls module's keys and nothing that says what draws the colour: no look manager answers,
    // so nx does not draw it and the controls are left out.
    private val data = WidgetDataRegistry()
    private val commands = WidgetCommandRegistry()

    init {
        ColourNxControls.contributions.filterIsInstance<KeyContribution>().forEach {
            registerModuleKeys(ColourNxControls.ID, it, data, commands, ContributedServices(), trusted = true)
        }
    }

    private val app = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, commands)

    /** The given slots of [surface] each holding one [Probe]. */
    private fun probed(surface: String, vararg slots: String) = LayoutGraph(
        surfaces = mapOf(
            SurfaceId(surface) to SurfaceLayout(
                slots = slots.associate { SlotId(it) to SlotContent(widgets = listOf(WidgetInstance(Probe.kind, "probe-$it"))) },
            ),
        ),
    )

    /** Draws [content] over [graph] and hands back the scene to read, closed by [read]. */
    private fun <T> drawn(graph: LayoutGraph, content: @Composable () -> Unit, read: (ImageComposeScene) -> T): T = onEventThread {
        val scene = ImageComposeScene(900, 2000, density = Density(1f)) {
            HostKoin(koin) {
                ShippedLanguageProvider("en") {
                    NxThemed(Themes.default, dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalLayoutGraph provides graph,
                            LocalWidgetRegistry provides registry,
                        ) { content() }
                    }
                }
            }
        }
        try {
            scene.render(scene.settle(8)).close()
            read(scene)
        } finally {
            scene.close()
        }
    }

    /** The height the settings Appearance section takes over [graph]. */
    private fun sectionHeight(graph: LayoutGraph): Int {
        var height = -1
        drawn(graph, {
            val form = remember { SettingsFormState(SettingsData()) }
            Column(Modifier.width(720.dp).onSizeChanged { height = it.height }) {
                AppearanceSection(
                    form = form,
                    save = {},
                    onOpenBackgroundSettings = {},
                    currentLanguage = NexiraStrings.translator("en").language,
                    languages = ShippedLanguages.all,
                    onLanguageChanged = {},
                )
            }
        }) {}
        return height
    }

    /**
     * How far the theme island's content scrolls over [graph], drawn short enough that it always
     * does: its content's height less the window's, read off the scroll the island reports.
     */
    private fun islandReach(graph: LayoutGraph): Int = drawn(graph, {
        AppearanceThemeIsland(
            surfaceBlur = true,
            onSurfaceBlurChanged = {},
            reduceMotion = false,
            onReduceMotionChanged = {},
            modifier = Modifier.width(320.dp).height(120.dp),
        )
    }) { scene ->
        val ranges = scene.semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
            .toList()
        assertEquals(1, ranges.size, "the island scrolls in one place")
        ranges.single().maxValue().toInt()
    }

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    @Test
    fun `the settings Appearance section takes the room of one without the controls' slots`() {
        val hidden = sectionHeight(DefaultLayout.load())
        assertEquals(hidden, sectionHeight(LayoutGraph.EMPTY), "the controls, left out, take what empty slots take")
        val probed = sectionHeight(probed("settings.appearance", "theme.link", "theme.daynight"))
        assertEquals(2 * (PROBE + SECTION_GAP), probed - hidden, "each slot left out takes its block and one gap with it")
    }

    @Test
    fun `the theme island takes the room of one without the controls' slots`() {
        val hidden = islandReach(DefaultLayout.load())
        assertEquals(hidden, islandReach(LayoutGraph.EMPTY), "the controls, left out, take what empty slots take")
        val probed = islandReach(probed("bg.settings", "theme", "theme.link"))
        assertEquals(2 * (PROBE + ISLAND_GAP), probed - hidden, "each slot left out takes its block and one gap with it")
    }

    /** A level with no services: the controls' condition is read from keys alone. */
    private object NoServices : LevelServicesHost {
        override val services: LevelServices = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null

            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no services here")
        }

        override fun child(level: ScopeLevel): LevelServicesHost = this

        override fun close() = Unit
    }

    private companion object {
        /** A height no row of either screen has, so a match is not a coincidence. */
        const val PROBE = 37

        /** The gap between the rows of the section and of the island, at a density of one. */
        const val SECTION_GAP = 12
        const val ISLAND_GAP = 16
    }
}
