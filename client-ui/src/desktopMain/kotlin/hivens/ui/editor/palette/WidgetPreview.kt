package hivens.ui.editor.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

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
     * The widget, trimmed to what it drew.
     *
     * The scene is larger than most widgets: one is 340 by 48 and is drawn at the
     * top of a 320 by 200 frame, so the raster is mostly nothing below it. The
     * trim happens once, here, rather than every time a tile paints: it is what
     * lets the tile take the widget's own proportions, and it is the difference
     * between holding a megabyte per preview and holding what the widget covers.
     */
    data class Drawn(val image: ImageBitmap) : WidgetPreview

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

    /**
     * The one thread previews are drawn on, and the lock that keeps it to one at
     * a time. Owned by the host, so [close] retires it with the gallery.
     */
    private val painter = newSingleThreadContext("widget-preview")
    private val gate = Mutex()

    /** Gives the drawing thread back. Called when the gallery leaves the composition. */
    fun close() {
        painter.close()
    }

    /** What is known about [kind] right now, without asking for it to be drawn. */
    fun peek(kind: WidgetKind): WidgetPreview = cache[kind] ?: WidgetPreview.Pending

    /**
     * Draws [kind] unless it is already drawn, and records the outcome.
     *
     * Off the interface's thread, and never on it. Composing a widget costs
     * between eighty and a hundred and forty milliseconds -- measured across the
     * whole registry, and it is the composition, not the raster: halving the pixel
     * scale made it no faster at all. A dozen visible tiles is therefore over a
     * second, and it was being spent on the thread that draws the editor, which is
     * how opening the palette came to freeze it.
     *
     * One dedicated thread rather than a pool. Each scene is built, drawn and
     * closed inside a single call so nothing crosses threads, but skia holds
     * native resources and a pool would scatter them over whichever worker was
     * free. One at a time for the same reason a gallery does not need two: a
     * reader looks at tiles in order, and a second core spent here is a core not
     * spent on the interface. On a machine slower than the one this was written
     * on, the tiles simply arrive further apart.
     */
    suspend fun draw(kind: WidgetKind, descriptor: WidgetDescriptor) {
        if (cache.containsKey(kind)) return
        val outcome = withContext(painter) {
            // Serialised, so a gallery that asks for twelve at once does not start
            // twelve scenes and leave the machine to arbitrate between them.
            gate.withLock { cache[kind] ?: render(kind, descriptor) }
        }
        // Written outside the lock: a snapshot write is safe from any thread, and
        // holding the lock across it would queue the gallery's own recomposition
        // behind the next render.
        cache[kind] = outcome
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(kind: WidgetKind, descriptor: WidgetDescriptor): WidgetPreview {
        // The frame is capped, and the widget is drawn into the capped frame rather
        // than into a larger one that gets cut. Clamping only the raster left a
        // widget declaring a 900-point preferred size composing at 900 inside a
        // scene 1024 pixels across, which on a 2x display is a preview of its left
        // half with nothing saying so.
        val box = fitToRaster(frameFor(descriptor.sizing), density.density)
        val w = (box.width * density.density).roundToInt().coerceAtLeast(1)
        val h = (box.height * density.density).roundToInt().coerceAtLeast(1)
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
                // render() hands back a skia Image; a bitmap is what the pixels can
                // be read out of. Both are full-frame rasters and neither outlives
                // this function: what is kept is the trimmed copy.
                val frame = try { Bitmap.makeFromImage(image) } finally { image.close() }
                try {
                    // A widget can compose without throwing and still draw nothing:
                    // an activity pill with no activity, a list with no items. An
                    // empty rectangle says less than the letter it would replace, so
                    // it is refused rather than shown.
                    trimmedToInk(frame)?.let { WidgetPreview.Drawn(it.asComposeImageBitmap()) }
                        ?: WidgetPreview.Refused
                } finally {
                    frame.close()
                }
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
 * The widget's own rectangle, copied out of the frame, or null if it drew too
 * little to be worth a tile.
 *
 * Ink is anything not transparent. The scene clears to transparent and the
 * preview paints no page behind the widget, so that is literally what "nothing
 * here" is. It used to compare against the corner pixel, on the belief that the
 * frame carried the theme's page colour: that reading breaks on a widget that
 * paints its own full-bleed plane, where the corner IS the widget and everything
 * matching it reads as empty. Eleven players and the theme grid declare
 * drawsOwnSurface, and only their rounded corners kept it working.
 *
 * Every pixel, from one read of the buffer. Sampling every fourth was a way to
 * keep the cost of a call-per-pixel down, and it cost correctness for it: a
 * hairline off the sampling grid was invisible, and whether it landed on one
 * depended on the display's scale, so the same widget had a preview on one
 * machine and a letter on another. One readPixels and a walk over the bytes is
 * both exact and cheaper than the sampling was.
 */
private fun trimmedToInk(frame: Bitmap): Bitmap? {
    val info = frame.imageInfo
    // Four-byte pixels with alpha last is what an N32 raster surface gives. A
    // frame in any other layout is kept whole rather than measured wrongly.
    if (info.bytesPerPixel != 4) return frame.copyOf(0, 0, info.width, info.height)
    val rowBytes = info.minRowBytes
    val pixels = frame.readPixels(info, rowBytes, 0, 0)
        ?: return frame.copyOf(0, 0, info.width, info.height)

    var left = info.width
    var top = info.height
    var right = -1
    var bottom = -1
    for (y in 0 until info.height) {
        val row = y * rowBytes
        for (x in 0 until info.width) {
            if (pixels[row + x * 4 + ALPHA_BYTE] == 0.toByte()) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            bottom = y
        }
    }
    if (right < 0) return null

    val w = right - left + 1
    val h = bottom - top + 1
    // Too little of the frame touched to read as anything. Measured against the
    // frame rather than against the trim, because a widget that inked four pixels
    // has a tiny trim and a huge magnification of nothing.
    if (w.toLong() * h < info.width.toLong() * info.height * MIN_INK) return null
    return frame.copyOf(left, top, w, h)
}

/**
 * A standalone copy of one rectangle of [this].
 *
 * A copy and not a subset: skia's extractSubset shares the source's pixels, and
 * the whole point here is that the frame goes away and only what the widget drew
 * is kept. Null when skia declines, which the caller reads as no preview.
 */
private fun Bitmap.copyOf(x: Int, y: Int, w: Int, h: Int): Bitmap? {
    val info = imageInfo.withWidthHeight(w, h)
    val out = Bitmap()
    if (!out.allocPixels(info)) return null
    val bytes = readPixels(info, info.minRowBytes, x, y) ?: return null
    if (!out.installPixels(info, bytes, info.minRowBytes)) return null
    return out
}

/** Alpha's place in an N32 pixel, which is the last byte on every platform we ship to. */
private const val ALPHA_BYTE = 3

/** Under this share of the frame touched, the letter says more than the picture. */
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

/**
 * [box] shrunk until its raster fits [MAX_PX] on both axes, keeping its shape.
 *
 * A tile is a thumbnail, so a widget that wants more than this gets drawn at a
 * size it fits in. That is a different picture from the one it draws at its own
 * size, and it is still a picture of the whole widget, which a clamped raster
 * around an unclamped layout is not.
 */
private fun fitToRaster(box: Size, density: Float): Size {
    val w = box.width * density
    val h = box.height * density
    val scale = minOf(1f, MAX_PX / w, MAX_PX / h)
    return if (scale >= 1f) box else Size(box.width * scale, box.height * scale)
}

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
    val host = remember(locals, density) { WidgetPreviewHost(locals, density) }
    // A real thread has to be given back. A theme change builds a new host, and
    // this retires the one it replaced.
    DisposableEffect(host) { onDispose { host.close() } }
    return host
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
