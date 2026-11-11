package hivens.ui.diag

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import org.jetbrains.skiko.SkiaLayer

/**
 * Resolves and publishes the backend for [window]. Cheap, and stable for the
 * process: Skiko picks the API once per layer, and the shell has one.
 *
 * [SkiaLayer] is a plain [javax.swing.JComponent] inside the window, so the
 * resolved value is read off the component tree rather than out of Compose's
 * internals.
 */
fun RenderBackend.probe(window: ComposeWindow?): String {
    published?.let { return it }
    val layer = window?.let { findSkiaLayer(it) } ?: return RenderBackend.UNKNOWN
    val resolved = runCatching { layer.renderApi.name }.getOrDefault(RenderBackend.UNKNOWN)
    publish(resolved)
    return resolved
}

private fun findSkiaLayer(root: Container): SkiaLayer? {
    for (child in root.components) {
        if (child is SkiaLayer) return child
        if (child is Container) findSkiaLayer(child)?.let { return it }
    }
    return null
}
