package hivens.ui.editor.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetFootprintDp
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSizing
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.resolveSurface
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WidgetPreview")

/**
 * What the gallery has for one widget.
 *
 * [Pending] is the state before anybody asked, [Drawn] a picture, and [Refused] a
 * widget that could not be composed outside the surface it belongs to. Refused is
 * an ordinary outcome rather than an error: a widget is free to need a context the
 * palette cannot hand it, and the tile falls back to its letter.
 */
sealed interface WidgetPreview {
    data object Pending : WidgetPreview

    /**
     * A picture, and the part of it the widget actually used.
     *
     * The frame is larger than most widgets: one is 340 by 48 and is drawn at the
     * top of a 320 by 200 scene, so the bitmap is mostly empty below it. Scaling
     * that whole bitmap into a tile scales the emptiness with it and leaves a
     * control the height of a hairline under a third of a tile of nothing. The
     * tile draws [inkOffset] by [inkSize] instead, which is the widget.
     */
    data class Drawn(
        val image: ImageBitmap,
        val inkOffset: IntOffset,
        val inkSize: IntSize,
    ) : WidgetPreview

    data object Refused : WidgetPreview
}

/**
 * Draws widgets off screen so the gallery can show the real thing.
 *
 * Android does not need this: its widgets are RemoteViews, a description the
 * launcher inflates, with no code in it to throw and no process of its own to be
 * missing. Ours are composables in the launcher's own composition, and Compose
 * forbids try/catch around a composable invocation, so a widget that throws takes
 * the shell with it. An off-screen scene is the way round that, because its
 * composition runs inside an ordinary function call.
 *
 * The net has to cover the CONSTRUCTOR and not the render. [ImageComposeScene]
 * composes its content in its constructor by way of setContent, so a widget has
 * already thrown before render() is reached, and a net around render() alone
 * catches nothing. Measured, not assumed -- see OffscreenPreviewProbeTest.
 *
 * The environment comes from [locals], captured from the live composition rather
 * than rebuilt. A separate scene inherits nothing, and rebuilding the theme, the
 * locale, the registries and the graph by hand would be a second copy of the
 * launcher's own wiring kept in step by hand. The drag ghost already carries its
 * locals across a composition boundary this way.
 */
@Stable
class WidgetPreviewHost internal constructor(
    private val locals: CompositionLocalContext,
    private val density: Density,
) {
    private val cache: SnapshotStateMap<WidgetKind, WidgetPreview> = mutableStateMapOf()

    /**
     * Stand-in sources, built once and handed to every preview.
     *
     * Several widgets show what is happening, and in a gallery nothing is. See
     * [previewDataRegistry] for why the live registry is replaced rather than
     * fallen back to.
     */
    private val data = previewDataRegistry()

    /** What is known about [kind] right now, without asking for it to be drawn. */
    fun peek(kind: WidgetKind): WidgetPreview = cache[kind] ?: WidgetPreview.Pending

    /**
     * Draws [kind] unless it is already drawn, and records the outcome.
     *
     * On the main dispatcher because a scene shares the launcher's AWT pump, and
     * driving one from another thread while a widget's own effects wait on that
     * pump is how an off-screen render deadlocks. One frame each, so the effects
     * mostly have not run and a widget shows the state it opens in.
     */
    suspend fun draw(kind: WidgetKind, descriptor: WidgetDescriptor) {
        if (cache.containsKey(kind)) return
        val outcome = withContext(Dispatchers.Main) {
            // Between widgets rather than inside one: a gallery asks for many at
            // once and the pump is shared with everything the reader can see.
            yield()
            render(kind, descriptor)
        }
        cache[kind] = outcome
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(kind: WidgetKind, descriptor: WidgetDescriptor): WidgetPreview {
        val box = frameFor(descriptor.sizing)
        val w = (box.width * density.density).toInt().coerceIn(1, MAX_PX)
        val h = (box.height * density.density).toInt().coerceIn(1, MAX_PX)
        var scene: ImageComposeScene? = null
        return runCatching {
            scene = ImageComposeScene(width = w, height = h, density = density) {
                CompositionLocalProvider(locals) {
                    // After the captured locals, so the stand-in sources win over
                    // the live ones the launcher is running on.
                    CompositionLocalProvider(LocalWidgetDataRegistry provides data) {
                        PreviewSubject(descriptor, kind, box)
                    }
                }
            }
            // render() hands back a skia Image; the bitmap is what Compose can draw.
            val bitmap = Bitmap.makeFromImage(scene.render())
            val ink = inkBounds(bitmap)
            // A widget can compose without throwing and still draw nothing: an
            // activity pill with no activity, a list with no items. An empty
            // rectangle says less than the letter it would replace, so it is
            // refused rather than shown.
            if (ink == null) WidgetPreview.Refused
            else WidgetPreview.Drawn(bitmap.asComposeImageBitmap(), ink.first, ink.second)
        }.onFailure { cause ->
            // Debug: a widget declining to compose outside its surface is normal,
            // and a palette that logs a warning per tile would drown the console
            // the first time it is opened.
            log.debug("no preview for {}: {}", kind.value, cause.toString())
        }.also {
            runCatching { scene?.close() }
        }.getOrElse { WidgetPreview.Refused }
    }
}

/**
 * The rectangle the widget actually drew into, or null if it drew too little to
 * be worth a tile.
 *
 * Measured against the corner pixel rather than against a named colour, because
 * the scene is drawn on whatever the theme's page is and "nothing here" is that,
 * not an absence. Sampled on a grid and then padded back out by the step, so the
 * crop never cuts into what it found: this runs once per widget and only has to
 * be right to within a few points.
 */
private fun inkBounds(bitmap: Bitmap): Pair<IntOffset, IntSize>? {
    val page = bitmap.getColor(0, 0)
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    var drawn = 0
    var seen = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            if (bitmap.getColor(x, y) != page) {
                drawn++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            seen++
            x += INK_STEP
        }
        y += INK_STEP
    }
    if (right < 0 || seen == 0 || drawn.toFloat() / seen < MIN_INK) return null
    val l = (left - INK_STEP).coerceAtLeast(0)
    val t = (top - INK_STEP).coerceAtLeast(0)
    val r = (right + INK_STEP).coerceAtMost(bitmap.width - 1)
    val b = (bottom + INK_STEP).coerceAtMost(bitmap.height - 1)
    return IntOffset(l, t) to IntSize(r - l + 1, b - t + 1)
}

private const val INK_STEP = 4

/** Under this the frame is empty enough that the letter says more. */
private const val MIN_INK = 0.004f

/**
 * The widget, mounted the way a slot would mount it.
 *
 * Deliberately the real render path and not a copy of it: the same surface
 * wrapper, the same declaration published, the same footprint. A preview drawn
 * down a second path would be a picture of something the launcher never draws.
 */
@Composable
private fun PreviewSubject(descriptor: WidgetDescriptor, kind: WidgetKind, box: Size) {
    val instance = remember(kind) { WidgetInstance(kind = kind, instanceId = "preview:${kind.value}") }
    CompositionLocalProvider(
        // Several widgets read the path and it errors when absent. The address is
        // fictional, which is the truth: a preview is not anywhere.
        LocalSlotPath provides PREVIEW_PATH,
        LocalWidgetSizing provides descriptor.sizing,
        LocalWidgetFootprintDp provides box,
    ) {
        Box(Modifier.size(box.width.dp, box.height.dp)) {
            val surface = descriptor.resolveSurface(instance)
            if (surface == null) {
                descriptor.Render(instance)
            } else {
                LocalWidgetSurfaceRenderer.current(surface) { descriptor.Render(instance) }
            }
        }
    }
}

/**
 * How large a frame to draw the widget into.
 *
 * Its declared preferred size where it has one, because that is the size it says
 * it looks correct at. Where it has none, a frame wide enough for the flow
 * widgets, which are the ones that declare nothing: they take the width they are
 * given and stand at whatever height their content comes to.
 */
internal fun frameFor(sizing: WidgetSizing): Size {
    val w = sizing.prefWidth.takeIf { it > 0 } ?: DEFAULT_FRAME_W
    val h = sizing.prefHeight.takeIf { it > 0 } ?: DEFAULT_FRAME_H
    return Size(w.toFloat(), h.toFloat())
}

private const val DEFAULT_FRAME_W = 320
private const val DEFAULT_FRAME_H = 200

/** A ceiling on the bitmap, so a widget declaring a huge preferred size costs a tile and not a screen. */
private const val MAX_PX = 1024

private val PREVIEW_PATH = SlotPath(SurfaceId("preview"), SlotId("preview"))

/** One host per palette, holding its cache for as long as the palette is open. */
@Composable
fun rememberWidgetPreviewHost(): WidgetPreviewHost {
    val locals = currentCompositionLocalContext
    val density = LocalDensity.current
    return remember(locals, density) { WidgetPreviewHost(locals, density) }
}

/**
 * The preview for [kind], drawn the first time a tile asks and cached after.
 *
 * Lazy by tile rather than eager for the registry: a gallery that drew sixty
 * widgets on open would spend the open doing it, and most of them are below the
 * fold.
 */
@Composable
fun rememberWidgetPreview(host: WidgetPreviewHost, kind: WidgetKind): WidgetPreview {
    val registry = LocalWidgetRegistry.current
    var state by remember(kind) { mutableStateOf(host.peek(kind)) }
    LaunchedEffect(host, kind) {
        val descriptor = registry[kind] ?: return@LaunchedEffect
        host.draw(kind, descriptor)
        state = host.peek(kind)
    }
    return state
}
