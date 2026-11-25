package hivens.ui

import javax.swing.SwingUtilities

/**
 * Runs [block] on the AWT event thread and hands back what it returned, or what it threw.
 *
 * For a scene whose content subscribes a `snapshotFlow`. Compose resumes those
 * collectors from its global apply pump on the event thread, outside any scene, and
 * a scene built and drawn on the test thread then has two threads in it: the
 * threshold overlay's test hung on it, and the news rail's broke the layout state
 * with "LayoutNode not found in RectList". One thread in the scene is the fix.
 */
fun <T> onEventThread(block: () -> T): T {
    var out: Result<T>? = null
    SwingUtilities.invokeAndWait { out = runCatching(block) }
    return out!!.getOrThrow()
}
