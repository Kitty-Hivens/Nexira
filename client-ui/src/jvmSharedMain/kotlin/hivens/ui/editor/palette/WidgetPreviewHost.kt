package hivens.ui.editor.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetSizing

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
 * Where the gallery gets its pictures from, and keeps them for as long as it is open.
 *
 * What draws them is the host's business: a desktop composes the widget into an
 * off-screen scene, and a host that cannot answers [WidgetPreview.Refused] for
 * every kind, which the tile reads as its letter.
 */
interface WidgetPreviewHost {
    /** What is known about [kind] right now, without asking for it to be drawn. */
    fun peek(kind: WidgetKind): WidgetPreview

    /** Draws [kind] unless it is already drawn, and records the outcome. */
    suspend fun draw(kind: WidgetKind, descriptor: WidgetDescriptor)

    /** Releases whatever the host holds. Called when the gallery leaves the composition. */
    fun close()
}

/** One host per palette, holding its cache for as long as the palette is open. */
@Composable
expect fun rememberWidgetPreviewHost(): WidgetPreviewHost

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
