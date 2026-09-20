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
import hivens.ui.editor.ShellChromeBounds
import hivens.ui.editor.LocalShellChromeBounds
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.LocalUnknownWidgetDecorator
import hivens.widget.api.LocalSlotChromeModifier
import hivens.widget.api.LocalSlotBoundsReporter
import hivens.widget.api.LocalEmptySlotDecorator
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
        withContext(Dispatchers.Main) {
            // Between widgets rather than inside one: a gallery asks for many at
            // once and the pump is shared with everything the reader can see.
            yield()
            // Recorded inside the dispatch. Written after it, a tile scrolled out
            // of the grid while its render was in flight cancelled the withContext
            // and threw away a finished picture, so scrolling back re-rendered it
            // from nothing every time.
            cache[kind] = render(kind, descriptor)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(kind: WidgetKind, descriptor: WidgetDescriptor): WidgetPreview {
        val box = frameFor(descriptor.sizing)
        val w = (box.width * density.density).toInt().coerceIn(1, MAX_PX)
        val h = (box.height * density.density).toInt().coerceIn(1, MAX_PX)
        // Built empty, then handed its content. Nothing of the widget's runs in
        // the constructor, so it cannot throw and the reference is in hand before
        // anything can. Composing inline instead loses the object with the throw:
        // the assignment never happens, close() is never reached, and an unclosed
        // scene leaves a Recomposer registered as a global snapshot apply-observer
        // for the life of the process, which every state write then walks. That is
        // a leak on exactly the path this whole file exists to make survivable.
        val scene = ImageComposeScene(width = w, height = h, density = density)
        try {
            return runCatching {
                scene.setContent {
                    CompositionLocalProvider(locals) {
                        PreviewEnvironment(data) { PreviewSubject(descriptor, kind, box) }
                    }
                }
                val image = scene.render()
                // render() hands back a skia Image; the bitmap is what Compose can
                // draw. Closed straight after: it is a second full-size raster and
                // only the bitmap outlives this function.
                val bitmap = try { Bitmap.makeFromImage(image) } finally { image.close() }
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
                // the first time it is opened. The cause goes in whole, because one
                // line naming neither the place nor the chain is not diagnostics.
                log.debug("no preview for {}", kind.value, cause)
            }.getOrElse { WidgetPreview.Refused }
        } catch (error: Throwable) {
            // Exceptions are the expected outcome and runCatching above owns them.
            // This is only here so the scene is released when something the process
            // cannot continue past goes by, and it is rethrown rather than turned
            // into a tile: an OutOfMemoryError recorded as "no preview" is a
            // launcher that quietly stops working.
            throw error
        } finally {
            runCatching { scene.close() }
        }
    }
}

/**
 * The rectangle the widget actually drew into, or null if it drew too little to
 * be worth a tile.
 *
 * Ink is anything not transparent. The scene clears to transparent and the
 * preview paints no page behind the widget, so that is literally what "nothing
 * here" is. It used to compare against the corner pixel, on the belief that the
 * frame carried the theme's page colour: that reading breaks on a widget that
 * paints its own full-bleed plane, where the corner IS the widget and everything
 * matching it reads as empty. Eleven players and the theme grid declare
 * drawsOwnSurface, and only their rounded corners kept it working.
 *
 * Sampled on a grid and then padded back out by the step, so the crop never cuts
 * into what it found: this runs once per widget and only has to be right to
 * within a few points.
 */
private fun inkBounds(bitmap: Bitmap): Pair<IntOffset, IntSize>? {
    val page = TRANSPARENT
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

/** What an untouched pixel of the scene is: [ImageComposeScene] clears to this. */
private const val TRANSPARENT = 0

/** Under this the frame is empty enough that the letter says more. */
private const val MIN_INK = 0.004f

/**
 * Everything the scene must NOT share with the editor it was launched from.
 *
 * The captured locals carry the editor's own wiring, and some of it writes. A
 * container widget renders a nested slot, the slot is empty, and the empty-slot
 * decorator registers its bounds in the LIVE drop-target registry -- in scene
 * coordinates, which the registry reads as window coordinates. The registry has
 * no way to unregister a slot, so the phantom outlives the palette: it is small,
 * and the hit-test picks the smallest rectangle containing the pointer, so a drop
 * anywhere near the window's top-left corner lands on a surface that does not
 * exist and silently does nothing.
 *
 * So the four editor hooks are stood down and the sources are substituted. The
 * shell's own measurements get a throwaway holder for the same reason: a preview
 * is not the shell and has no business reporting where the content pane is.
 */
@Composable
private fun PreviewEnvironment(data: WidgetDataRegistry, content: @Composable () -> Unit) {
    val ownBounds = remember { ShellChromeBounds() }
    CompositionLocalProvider(
        LocalWidgetDataRegistry provides data,
        LocalEmptySlotDecorator provides {},
        LocalSlotBoundsReporter provides { _, _ -> },
        LocalSlotChromeModifier provides { _, _ -> Modifier },
        LocalWidgetDecorator provides { _, _, _, _, inner -> inner() },
        LocalUnknownWidgetDecorator provides { _, _, _ -> },
        LocalShellChromeBounds provides ownBounds,
        content = content,
    )
}

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

/**
 * One host per palette, holding its cache for as long as the palette is open.
 *
 * Call it ABOVE whatever branches on the search: remembered inside a branch, a
 * query that matches nothing takes the host out of the composition and clearing
 * the field builds a new one, so every preview is lost to a typo.
 */
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
