package hivens.ui.platform

import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Fire-and-forget wrappers around `java.awt.Desktop` that always dispatch
 * the native invocation onto a daemon thread.
 *
 * `Desktop.getDesktop().open(...)` and `.browse(...)` nominally fork the
 * configured handler and return promptly, but in practice on Linux/Wayland
 * with a stalled `xdg-desktop-portal` D-Bus -- or any flavor of broken
 * `xdg-open` -- the call blocks for seconds. Compose Desktop drives
 * `onClick` lambdas on the AWT EDT, and puppet's `withContext(Dispatchers.Swing)`
 * routes HTTP click handlers through the same thread. A few seconds spent
 * blocking inside `Desktop.X` therefore freezes the entire UI.
 *
 * Each helper here spawns a `daemon = true` thread, runs the native call
 * inside a `runCatching`, and returns immediately. Failures are silent --
 * the caller's expected outcome is "OS opens the resource"; if it doesn't,
 * there's no useful surface for an error message and the user-visible
 * result (no file manager popping up) is the same as success-but-quiet.
 *
 * Daemon flag matters: a hung native call must not pin the JVM at exit.
 * The thread is short-lived in the common case; the daemon flag bounds
 * the failure case.
 */
internal object SystemActions {

    /**
     * Open a directory in the OS file manager. Creates the directory if
     * it doesn't exist (so a freshly-launched user without crash reports
     * can still click "Open crash reports" and see an empty folder
     * rather than a "no such file" dialog).
     */
    fun openFolder(path: String) {
        runOnThread("aura-open-folder") {
            if (!Desktop.isDesktopSupported()) return@runOnThread
            val dir = File(path).also { it.mkdirs() }
            Desktop.getDesktop().open(dir)
        }
    }

    /** Open a specific file via the OS' associated handler. */
    fun openFile(file: File) {
        runOnThread("aura-open-file") {
            if (!Desktop.isDesktopSupported()) return@runOnThread
            Desktop.getDesktop().open(file)
        }
    }

    /**
     * Open a URL in the user's browser. Guards on the BROWSE action
     * being supported (headless servers / minimal Linux installs may
     * not advertise it).
     */
    fun openUrl(url: String) {
        runOnThread("aura-open-url") {
            if (!Desktop.isDesktopSupported()) return@runOnThread
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runOnThread
            desktop.browse(URI(url))
        }
    }

    private inline fun runOnThread(name: String, crossinline body: () -> Unit) {
        Thread({ runCatching { body() } }, name).apply {
            isDaemon = true
            start()
        }
    }
}
