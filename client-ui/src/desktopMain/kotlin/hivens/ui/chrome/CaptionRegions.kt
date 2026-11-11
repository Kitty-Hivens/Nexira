package hivens.ui.chrome

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where on the window a press moves it, for a frame drawn by the system (see
 * [Win32Frame]): the empty part of the top bar, minus what sits in it.
 *
 * Written from the UI thread as the layout moves, read from the thread that
 * answers the system's hit tests, so every change publishes a fresh snapshot
 * rather than mutating what a reader may be holding. Coordinates are the
 * window's client pixels, which is what Compose lays out in.
 */
class CaptionRegions {
    private class Snapshot(val captions: List<Rect>, val exclusions: List<Rect>)

    @Volatile private var snapshot = Snapshot(emptyList(), emptyList())
    private val captions = LinkedHashMap<Any, Rect>()
    private val exclusions = LinkedHashMap<Any, Rect>()

    /** Whether the window manager lets the window be resized; the edges answer only then. */
    @Volatile var resizable: Boolean = true

    fun isCaption(x: Int, y: Int): Boolean {
        val s = snapshot
        val fx = x.toFloat()
        val fy = y.toFloat()
        return s.captions.any { it.contains(fx, fy) } && s.exclusions.none { it.contains(fx, fy) }
    }

    internal fun put(key: Any, bounds: Rect?, exclusion: Boolean) = synchronized(this) {
        val map = if (exclusion) exclusions else captions
        if (bounds == null) map.remove(key) else map[key] = bounds
        snapshot = Snapshot(captions.values.toList(), exclusions.values.toList())    }
}

/** Marks this element's bounds as a place a press moves the window, for as long as it is composed. */
fun Modifier.captionRegion(regions: CaptionRegions?): Modifier = trackIn(regions, exclusion = false)

/** Marks this element's bounds as taking its own clicks inside a [captionRegion]. */
fun Modifier.captionExclusion(regions: CaptionRegions?): Modifier = trackIn(regions, exclusion = true)

private fun Modifier.trackIn(regions: CaptionRegions?, exclusion: Boolean): Modifier {
    if (regions == null) return this
    return composed {
        val key = remember { Any() }
        DisposableEffect(regions, key) { onDispose { regions.put(key, null, exclusion) } }
        onGloballyPositioned { regions.put(key, it.boundsInWindow(), exclusion) }
    }
}

private fun Rect.contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
