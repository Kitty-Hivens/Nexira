package hivens.ui.diag

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import org.jetbrains.skiko.SkiaLayer

/**
 * Which graphics backend the shell is actually drawing through.
 *
 * `skiko.renderApi` is a request, and the launcher never sets it, so every
 * readout built on that property says "default" no matter what Skiko settled
 * on. The distinction the property cannot make is the one that matters: a
 * driver that refuses a device drops the whole UI onto `SOFTWARE_FAST`, where a
 * full-screen window is rasterised on the CPU and the cost lands on the machine
 * rather than on the launcher alone. Reported as "everything went slow", with
 * nothing in a bundle to separate it from a launcher that is merely busy.
 *
 * [SkiaLayer] is a plain [javax.swing.JComponent] inside the window, so the
 * resolved value is read off the component tree rather than out of Compose's
 * internals.
 */
object RenderBackend {

    @Volatile
    private var cached: String? = null

    /** The resolved backend, or `unknown` before a window has been probed. */
    val current: String get() = cached ?: UNKNOWN

    /**
     * Resolves and caches the backend for [window]. Cheap, and stable for the
     * process: Skiko picks the API once per layer, and the shell has one.
     */
    fun probe(window: ComposeWindow?): String {
        cached?.let { return it }
        val layer = window?.let { findSkiaLayer(it) } ?: return UNKNOWN
        val resolved = runCatching { layer.renderApi.name }.getOrDefault(UNKNOWN)
        cached = resolved
        return resolved
    }

    private fun findSkiaLayer(root: Container): SkiaLayer? {
        for (child in root.components) {
            if (child is SkiaLayer) return child
            if (child is Container) findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    private const val UNKNOWN = "unknown"
}
