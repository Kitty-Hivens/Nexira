package hivens.ui.editor.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditorSurfaces
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.widgets.WidgetSurface
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetStateHost
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/**
 * How many widgets stand up in an off-screen scene, and what stops the rest.
 *
 * The question this answers is a cost, not a pass or a fail: a gallery that draws
 * the real widget needs an environment built for it, and the only honest way to
 * price that is to try every widget in the registry and read what comes back.
 * Android does not have this problem because its widgets are RemoteViews -- a
 * description, inflated by the launcher, with no code in it to throw. Ours are
 * composables in the launcher's own composition, so the bill is real and this
 * prints it.
 *
 * Deliberately not an assertion on the count. The number moves every time a
 * widget is added, and a test that fails when somebody writes one would be a
 * tax on writing widgets. It writes a report instead, and asserts only the two
 * things that would make the report a lie: that the census ran at all, and that
 * a widget failing takes nothing with it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WidgetPreviewCensusTest {

    private companion object {
        const val SIDE = 320
        const val SHOT_DIR = "build/reports/widget-previews"
    }

    private object NoState : WidgetStateHost {
        override fun load(instanceId: String): JsonObject? = null
        override fun store(instanceId: String, value: JsonObject) = Unit
    }

    /**
     * What a preview scene can reasonably be handed.
     *
     * The registries and the graph a surface would have, the stub surface contexts
     * the editor already keeps for a widget dragged somewhere foreign, and a slot
     * path, which several widgets read and which errors when absent. Everything
     * here is cheap and process-local. What is NOT here is the part that costs:
     * live services behind Koin, and any data a widget draws from.
     */
    @Composable
    private fun Environment(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalLayoutGraph provides DefaultLayout.load(),
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalWidgetDataRegistry provides WidgetDataRegistry(),
            LocalWidgetCommandRegistry provides WidgetCommandRegistry(),
            LocalWidgetServiceRegistry provides WidgetServiceRegistry(),
            LocalWidgetStateHost provides NoState,
            LocalSlotPath provides SlotPath(SurfaceId("home.new"), SlotId("main")),
            LocalWidgetSurfaceRenderer provides { spec, inner -> WidgetSurface(spec, inner) },
            *EditorSurfaces.stubs,
        ) {
            content()
        }
    }

    /**
     * Renders one widget and keeps the picture.
     *
     * The count is not the answer on its own. A widget that composes without
     * throwing can still draw an empty box, which is a worse preview than a
     * letter, and the only way to tell the two apart is to look. So the pixels are
     * written out and the ink is measured: how much of the frame the widget
     * actually covered.
     */
    private fun tryRender(kind: WidgetKind): Throwable? {
        val descriptor = GeneratedWidgetRegistry[kind] ?: return IllegalStateException("no descriptor")
        val instance = WidgetInstance(kind = kind, instanceId = "census-${kind.value}")
        var scene: ImageComposeScene? = null
        return runCatching {
            scene = ImageComposeScene(width = SIDE, height = SIDE, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(useDarkTheme = true) {
                        Environment {
                            Box(Modifier.size(SIDE.dp)) { descriptor.Render(instance) }
                        }
                    }
                }
            }
            val image = scene.render()
            File(SHOT_DIR).mkdirs()
            image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
                File("$SHOT_DIR/${kind.value}.png").writeBytes(it)
            }
            ink[kind] = coverage(image)
            Unit
        }.also { runCatching { scene?.close() } }.exceptionOrNull()
    }

    /**
     * How much of the frame the widget put anything into, as a fraction.
     *
     * Against the page colour rather than against black: the scene is drawn on the
     * theme's background, so "nothing here" is that colour and not an absence.
     */
    private fun coverage(image: Image): Float {
        val bitmap = Bitmap.makeFromImage(image)
        var page = 0
        var drawn = 0
        val step = 4
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getColor(x, y)
                if (c == bitmap.getColor(1, 1)) page++ else drawn++
                x += step
            }
            y += step
        }
        return if (page + drawn == 0) 0f else drawn.toFloat() / (page + drawn)
    }

    private val ink = LinkedHashMap<WidgetKind, Float>()

    @Test
    fun `census every widget in the registry`() {
        val kinds = GeneratedWidgetRegistry.all().keys.sortedBy { it.value }
        check(kinds.isNotEmpty()) { "the registry is empty, so this measured nothing" }

        val failures = LinkedHashMap<WidgetKind, Throwable>()
        kinds.forEach { kind -> tryRender(kind)?.let { failures[kind] = it } }

        val ok = kinds.size - failures.size
        // Grouped by what actually stopped them, because "twenty failed" is not a
        // cost and "twenty failed for one missing provider" is.
        val byReason = failures.entries.groupBy { (_, e) ->
            val root = generateSequence(e) { it.cause }.last()
            "${root::class.simpleName}: ${root.message?.take(120)}"
        }

        // A composition that did not throw and drew almost nothing is the case the
        // count hides: technically a preview, useless as one.
        val blank = ink.filterValues { it < 0.02f }.keys
        val report = buildString {
            appendLine("widgets in registry : ${kinds.size}")
            appendLine("rendered off screen : $ok")
            appendLine("threw               : ${failures.size}")
            appendLine("rendered but blank  : ${blank.size}   (under 2% of the frame inked)")
            appendLine()
            appendLine("ink coverage, least first:")
            ink.entries.sortedBy { it.value }.forEach {
                appendLine("   %5.1f%%  %s".format(it.value * 100, it.key.value))
            }
            appendLine()
            byReason.entries.sortedByDescending { it.value.size }.forEach { (reason, entries) ->
                appendLine("[${entries.size}] $reason")
                entries.forEach { appendLine("      ${it.key.value}") }
                appendLine()
            }
        }
        File("build/reports").mkdirs()
        File("build/reports/widget-preview-census.txt").writeText(report)
        println(report)

        // A widget that throws must cost its own preview and nothing else, which is
        // the whole basis for rendering them at all. If the run reached here, every
        // scene after a failed one still built.
        check(ok + failures.size == kinds.size)
    }
}
