package hivens.window

import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.Timer

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
 * first restore picks a size of its own (see [WindowHost]).
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
 * is on. [floor] is what [initialWindowSize] raises a small work area to.
 */
fun openingFrame(
    saved: SavedWindow?,
    workAreas: List<Rectangle>,
    primary: Rectangle,
    restore: Dimension,
    floor: Dimension,
    placeable: Boolean,
): OpeningFrame {
    if (saved == null || !placeable) return OpeningFrame(maximizedFrame(primary, restore, floor), maximized = true, normal = null)
    val normal = fitToScreens(saved.normalBounds, workAreas) ?: restoredBounds(primary, restore)
    if (!saved.maximized) return OpeningFrame(normal, maximized = false, normal = normal)
    val centre = Point(normal.centerX.toInt(), normal.centerY.toInt())
    val area = workAreas.firstOrNull { it.contains(centre) } ?: primary
    return OpeningFrame(maximizedFrame(area, restore, floor), maximized = true, normal = normal)
}

/** The frame a maximised window is born with: its work area, at the size [initialWindowSize] gives it. */
private fun maximizedFrame(area: Rectangle, restore: Dimension, floor: Dimension): Rectangle {
    val size = initialWindowSize(area, restore, floor)
    return Rectangle(area.x, area.y, size.width, size.height)
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

/**
 * Writes [SavedWindow] as the window changes, so the next start opens it the
 * way it was left.
 *
 * A move, a resize or a change of state only restarts a wait of [SETTLE_MS]. The
 * record is read from the frame once the window has stopped changing: entering
 * and leaving maximised fires resize events at sizes that belong to neither
 * state, and on some systems before the state itself has flipped, so a frame
 * recorded at the event could store the maximised rectangle as the normal one.
 * A minimised or hidden frame never gives its frame as the normal one. The file
 * is written off the event thread, except by [close], which writes in place on
 * the way out, since the process may be gone before another thread runs.
 */
class WindowSessionRecorder internal constructor(
    private val host: WindowHost,
    initialNormal: Rectangle?,
    private val write: (SavedWindow) -> Unit,
) : AutoCloseable {
    private var normal: Rectangle? = initialNormal
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "window-session").apply { isDaemon = true }
    }
    private val settle = Timer(SETTLE_MS) { flushLater() }.apply { isRepeats = false }
    private val removals = listOf(
        host.frame.onGeometryChanged { settle.restart() },
        host.onStatusChanged { settle.restart() },
    )

    /**
     * The record as the frame is now, or null before the window has had a
     * normal frame at all. Reads the frame, so call it on the AWT thread.
     */
    internal fun snapshot(): SavedWindow? {
        val status = host.status.value
        if (!status.maximized && !status.minimized && host.frame.showing) normal = host.frame.bounds
        return normal?.let { SavedWindow.of(status.maximized, it) }
    }

    private fun flushLater() {
        val record = snapshot() ?: return
        writer.execute { save(record) }
    }

    private fun save(record: SavedWindow) {
        runCatching { write(record) }
            .onFailure { log.warn("Could not save the window's size and position", it) }
    }

    override fun close() {
        settle.stop()
        removals.forEach { it() }
        writer.shutdown()
        snapshot()?.let(::save)
    }

    companion object {
        const val SETTLE_MS = 400
        private val log: Logger = LoggerFactory.getLogger(WindowSessionRecorder::class.java)

        /** Starts recording [host]'s window into `window.json` under [dataDir]. Call on the AWT thread. */
        fun start(host: WindowHost, dataDir: Path, initialNormal: Rectangle?): WindowSessionRecorder =
            WindowSessionRecorder(host, initialNormal) { SavedWindow.write(dataDir, it) }
    }
}

/** How much of the window's top must stay on a screen for it to be grabbed and moved. */
private const val GRAB_STRIP = 32
private const val GRAB_MIN = 96
