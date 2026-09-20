package hivens.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditorSurfaces
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.WidgetSizing
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A widget that scrolls, in a slot that was given no height.
 *
 * Compose refuses to measure a scrolling or lazily listing component against an
 * unbounded axis: it throws rather than guess how much of an infinite column to
 * build. That is reachable from the editor, which lets any widget be dropped in
 * any slot, and a slot inherits whatever its surface hands down. Two surfaces
 * avoid it today by not scrolling around a slot, each with a comment saying so,
 * which is a rule kept by hand wherever somebody remembered.
 *
 * Six widgets in the registry scroll or lazily list. The renderer now fills an
 * unbounded axis from what the widget declared it can use, so the declaration is
 * what stands between those six and a crash. This pins both halves: that the
 * declaration is honoured, and that its absence is still the old behaviour rather
 * than a ceiling this file invented.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UnboundedSlotTest {

    private val surface = SurfaceId("s")
    private val slot = SlotId("main")
    private val kind = WidgetKind("scroller")

    /** Taller than any frame it will be given, so it has to scroll rather than fit. */
    private class Scroller(
        override val kind: WidgetKind,
        override val sizing: WidgetSizing,
    ) : WidgetDescriptor {
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                repeat(40) { Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Red)) }
            }
        }
    }

    private class Registry(private val one: WidgetDescriptor) : WidgetRegistry {
        override fun all() = mapOf(one.kind to one)
        override fun get(kind: WidgetKind) = one.takeIf { it.kind == kind }
    }

    /**
     * Renders the slot inside a parent with no height to give.
     *
     * The scroll on the outer column is what makes the axis unbounded, and it is
     * also exactly the shape a surface takes when somebody wraps its content to
     * make a long page scroll. Nothing here is contrived.
     */
    private fun renderUnbounded(sizing: WidgetSizing, flow: Boolean): Result<Unit> {
        val content = SlotContent(
            widgets = listOf(
                WidgetInstance(
                    kind = kind,
                    instanceId = "one",
                    placement = if (flow) null else Placement(),
                ),
            ),
            flow = if (flow) hivens.widget.model.FlowSpec.Column else null,
        )
        val graph = LayoutGraph(surfaces = mapOf(surface to SurfaceLayout(slots = mapOf(slot to content))))
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f))
        return runCatching {
            scene.setContent {
                CompositionLocalProvider(
                    LocalLayoutGraph provides graph,
                    LocalWidgetRegistry provides Registry(Scroller(kind, sizing)),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SlotRenderer(surface, slot, Modifier.fillMaxWidth())
                    }
                }
            }
            scene.render().close()
        }.also { runCatching { scene.close() } }
    }

    @Test
    fun `a declared ceiling is what the unbounded axis is measured against`() {
        val ok = renderUnbounded(WidgetSizing(maxWidth = 400, maxHeight = 300), flow = false)
        assertTrue(
            ok.isSuccess,
            "a scrolling widget in a slot with no height still cannot be measured: ${ok.exceptionOrNull()}",
        )
    }

    @Test
    fun `the ceiling reaches a flow slot too, not only a placed widget`() {
        val ok = renderUnbounded(WidgetSizing(maxWidth = 400, maxHeight = 300), flow = true)
        assertTrue(
            ok.isSuccess,
            "the flow branch goes through a different wrapper, and it was missed: ${ok.exceptionOrNull()}",
        )
    }

    @Test
    fun `a height alone is enough, because height is the axis that is unbounded here`() {
        val ok = renderUnbounded(WidgetSizing(maxHeight = 300), flow = false)
        assertTrue(ok.isSuccess, "${ok.exceptionOrNull()}")
    }

    @Test
    fun `a widget that declared nothing is left exactly as it was`() {
        // Not a gap in the fix. Inventing a ceiling for an undeclared widget would
        // be the renderer guessing at a size, and what those widgets need is the
        // declaration they are missing. Pinned so the day one is invented, this
        // says where.
        val threw = renderUnbounded(WidgetSizing(), flow = false)
        assertTrue(
            threw.isFailure,
            "an undeclared scroller now measures against something: whatever ceiling that is, it was invented here",
        )
    }

    @Test
    fun `a declared width does not silence an unbounded height`() {
        val threw = renderUnbounded(WidgetSizing(maxWidth = 400), flow = false)
        assertTrue(threw.isFailure, "the width was substituted on the height's behalf")
    }

    /**
     * The same thing with a real widget off the real registry.
     *
     * The synthetic scroller above proves the mechanism. This proves it reaches
     * the widget it was written for: the credits panel scrolls its own content,
     * it is droppable from the palette like anything else, and before its
     * declaration it took the shell down wherever it landed in a slot with no
     * height to give.
     */
    private fun renderReal(kind: WidgetKind): Result<Unit> {
        val content = SlotContent(
            widgets = listOf(WidgetInstance(kind = kind, instanceId = "real", placement = Placement())),
            flow = null,
        )
        val graph = LayoutGraph(surfaces = mapOf(surface to SurfaceLayout(slots = mapOf(slot to content))))
        val scene = ImageComposeScene(width = 320, height = 320, density = Density(1f))
        return runCatching {
            scene.setContent {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(useDarkTheme = true) {
                        CompositionLocalProvider(
                            LocalLayoutGraph provides graph,
                            LocalWidgetRegistry provides GeneratedWidgetRegistry,
                            LocalWidgetSurfaceRenderer provides { spec, inner -> WidgetSurface(spec, inner) },
                            *EditorSurfaces.stubs,
                        ) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                SlotRenderer(surface, slot, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            scene.render().close()
        }.also { runCatching { scene.close() } }
    }

    @Test
    fun `the credits panel survives a slot that gives it no height`() {
        val ok = renderReal(WidgetKind("about.credits"))
        assertTrue(ok.isSuccess, "${ok.exceptionOrNull()}")
    }

    @Test
    fun `the theme grid survives one too`() {
        val ok = renderReal(WidgetKind("theme.picker.grid"))
        assertTrue(ok.isSuccess, "${ok.exceptionOrNull()}")
    }
}
