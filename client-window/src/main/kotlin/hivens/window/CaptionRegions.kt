package hivens.window

/**
 * Where on the window a press moves it, for a frame drawn by the system (see
 * [Win32Frame]): the empty part of the top bar, minus what sits in it.
 *
 * Written from the UI thread as the layout moves, read from the thread that
 * answers the system's hit tests, so every change publishes a fresh snapshot
 * rather than mutating what a reader may be holding. Coordinates are the
 * window's client pixels, which is what the UI lays out in.
 */
class CaptionRegions {
    /** A rectangle in client pixels, its right and bottom edges outside it. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    private class Snapshot(val captions: List<Area>, val exclusions: List<Area>)

    @Volatile private var snapshot = Snapshot(emptyList(), emptyList())
    private val captions = LinkedHashMap<Any, Area>()
    private val exclusions = LinkedHashMap<Any, Area>()

    /** Whether the window manager lets the window be resized; the edges answer only then. */
    @Volatile var resizable: Boolean = true

    fun isCaption(x: Int, y: Int): Boolean {
        val s = snapshot
        val fx = x.toFloat()
        val fy = y.toFloat()
        return s.captions.any { it.contains(fx, fy) } && s.exclusions.none { it.contains(fx, fy) }
    }

    /**
     * Records [bounds] under [key] as a caption, or as an exclusion inside one,
     * and forgets the key with a null.
     */
    fun put(key: Any, bounds: Area?, exclusion: Boolean) = synchronized(this) {
        val map = if (exclusion) exclusions else captions
        if (bounds == null) map.remove(key) else map[key] = bounds
        snapshot = Snapshot(captions.values.toList(), exclusions.values.toList())
    }
}
