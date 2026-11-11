package hivens.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * How the window was left: maximised or not, and the frame it had the last time
 * it was not, which is where a restore takes it back to.
 *
 * Kept in a file of its own beside the settings rather than in them. It changes
 * every time the window is moved, and it is read before the settings service
 * exists, so it never goes through the service's lock or its save path.
 */
@Serializable
data class SavedWindow(
    val maximized: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val normalBounds: Rectangle get() = Rectangle(x, y, width, height)

    companion object {
        const val FILE = "window.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Null for a first run, and for a file that cannot be read: the window then opens as it always has. */
        fun read(dataDir: Path): SavedWindow? = runCatching {
            val file = dataDir.resolve(FILE)
            if (!Files.isRegularFile(file)) return@runCatching null
            json.decodeFromString(serializer(), readUtf8(file)).takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()

        fun write(dataDir: Path, saved: SavedWindow) {
            AtomicFiles.writeString(dataDir.resolve(FILE), json.encodeToString(serializer(), saved))
        }

        fun of(maximized: Boolean, normal: Rectangle) =
            SavedWindow(maximized, normal.x, normal.y, normal.width, normal.height)
    }
}

/**
 * What the window opens as. [bounds] is the frame it is created with, [normal]
 * the frame its first restore gives it, or null where nothing was saved and the
 * first restore picks a size of its own (see [WindowMaximizer]).
 */
data class OpeningFrame(val bounds: Rectangle, val maximized: Boolean, val normal: Rectangle?)

/**
 * Decides [OpeningFrame] from what was saved and the screens there are now.
 *
 * Nothing saved, or a window manager that places windows itself ([placeable]
 * false), opens maximised on [primary] as before. A saved frame is used only
 * while its top strip, the part that is grabbed to move the window, is still on
 * some screen: a monitor unplugged since, or a resolution dropped, would
 * otherwise bring the window back where nobody can reach it. A frame that
 * survives is shrunk to the screen it is on and pulled in until that strip is
 * reachable. A frame that does not falls back to the restore size, centred on
 * [primary]. A maximised window opens maximised on the screen its normal frame
 * is on.
 */
fun openingFrame(
    saved: SavedWindow?,
    workAreas: List<Rectangle>,
    primary: Rectangle,
    restore: DpSize,
    placeable: Boolean,
): OpeningFrame {
    if (saved == null || !placeable) return OpeningFrame(maximizedFrame(primary, restore), maximized = true, normal = null)
    val normal = fitToScreens(saved.normalBounds, workAreas) ?: restoredBounds(primary, restore)
    if (!saved.maximized) return OpeningFrame(normal, maximized = false, normal = normal)
    val centre = Point(normal.centerX.toInt(), normal.centerY.toInt())
    val area = workAreas.firstOrNull { it.contains(centre) } ?: primary
    return OpeningFrame(maximizedFrame(area, restore), maximized = true, normal = normal)
}

/** The frame a maximised window is born with: its work area, at the size [initialWindowSize] gives it. */
private fun maximizedFrame(area: Rectangle, restore: DpSize): Rectangle {
    val size = initialWindowSize(area, restore)
    return Rectangle(area.x, area.y, size.width.value.toInt(), size.height.value.toInt())
}

internal fun fitToScreens(frame: Rectangle, workAreas: List<Rectangle>): Rectangle? {
    val strip = Rectangle(frame.x, frame.y, frame.width, GRAB_STRIP)
    val area = workAreas.maxByOrNull { it.intersection(strip).let { i -> if (i.isEmpty) 0 else i.width } } ?: return null
    val reachable = area.intersection(strip)
    if (reachable.isEmpty || reachable.width < GRAB_MIN) return null
    val w = minOf(frame.width, area.width)
    val h = minOf(frame.height, area.height)
    val x = frame.x.coerceIn(area.x - w + GRAB_MIN, area.x + area.width - GRAB_MIN)
    val y = frame.y.coerceIn(area.y, area.y + area.height - GRAB_STRIP)
    return Rectangle(x, y, w, h)
}

/** The work area of every screen attached now, the primary first. Empty when headless. */
fun currentWorkAreas(): List<Rectangle> = runCatching {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = env.defaultScreenDevice
    (listOf(primary) + env.screenDevices.filter { it !== primary })
        .map { screenWorkArea(it.defaultConfiguration) }
        .filter { it.width > 0 && it.height > 0 }
}.getOrDefault(emptyList())

/**
 * Writes [SavedWindow] as the window changes, so the next start opens it the
 * way it was left.
 *
 * Moves and resizes only mark the record dirty. It is read back from the frame
 * [SETTLE_MS] later, once the window has stopped changing: entering and leaving
 * maximised fires resize events at sizes that belong to neither state, and on
 * some systems before the state itself has flipped, so a frame recorded at the
 * event could store the maximised rectangle as the normal one. A minimised or
 * hidden frame is never recorded.
 */
class WindowSessionWriter(
    private val dataDir: Path,
    private val maximizer: WindowMaximizer,
    initialNormal: Rectangle?,
) {
    private var normal: Rectangle? = initialNormal

    /** Bumped on every move or resize, watched by the caller to time the write. */
    var changes by mutableStateOf(0)
        private set

    private var window: ComposeWindow? = null
    private val listener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) { changes++ }
        override fun componentResized(e: ComponentEvent) { changes++ }
    }

    fun attach(w: ComposeWindow) {
        window = w
        w.addComponentListener(listener)
    }

    fun detach() {
        window?.removeComponentListener(listener)
        window = null
    }

    /**
     * The record as the frame is now, or null before the window has had a
     * normal frame at all. Reads the frame, so call it on the AWT thread.
     */
    fun snapshot(): SavedWindow? {
        val w = window ?: return null
        val maximized = maximizer.maximized
        val minimized = (w.extendedState and Frame.ICONIFIED) != 0
        if (!maximized && !minimized && w.isShowing) normal = w.bounds
        return normal?.let { SavedWindow.of(maximized, it) }
    }

    /** Writes [record]. A file write, so off the AWT thread except on the way out. */
    fun save(record: SavedWindow) {
        runCatching { SavedWindow.write(dataDir, record) }
            .onFailure { log.warn("Could not save the window's size and position", it) }
    }

    companion object {
        const val SETTLE_MS = 400L
        private val log: Logger = LoggerFactory.getLogger(WindowSessionWriter::class.java)
    }
}

/** How much of the window's top must stay on a screen for it to be grabbed and moved. */
private const val GRAB_STRIP = 32
private const val GRAB_MIN = 96
