package hivens.ui.diag

import hivens.config.Branding
import hivens.launcher.CrashReporter
import hivens.launcher.diag.IssueReporter
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JOptionPane

/**
 * The post-crash Swing dialog: open the saved report in the file manager,
 * copy it, or pre-fill a GitHub Issue with it. Lives in the UI layer -- the
 * report itself is generated and persisted headlessly by [CrashReporter];
 * this is the one consumer that needs a toolkit.
 */
object CrashDialog {

    fun show(report: CrashReporter.CrashReport, reportFile: File) {
        val message = """
            Nexira quit unexpectedly.

            Report saved:
            ${reportFile.absolutePath}

            Please send this file to the developers.
        """.trimIndent()

        val options = arrayOf("Report on GitHub", "Copy report", "Open folder", "Close")
        val choice = JOptionPane.showOptionDialog(
            null,
            message,
            "Crash Report -- ${Branding.TITLE}",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options[0],
        )

        when (choice) {
            0 -> {
                // Beacon "Report on GitHub": opens browser at a pre-filled new-Issue
                // URL. Nothing leaves the user's machine until they click Submit on
                // github.com -- the launcher itself never POSTs anything.
                openOnDaemonThread("crash-report-browse") {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(IssueReporter.crashIssueUrl(report)))
                    }
                }
            }
            1 -> {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(reportFile.readText()), null)
            }
            2 -> {
                openOnDaemonThread("crash-report-open-folder") {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(reportFile.parentFile)
                    }
                }
            }
        }
    }

    /**
     * Fire-and-forget native Desktop call on a daemon thread. `Desktop.open` /
     * `Desktop.browse` can stall for seconds on Linux/Wayland when the
     * `xdg-desktop-portal` D-Bus is wedged; running them on the calling
     * thread (which here is AWT EDT, since this is invoked from
     * `JOptionPane.showOptionDialog`'s choice handler) freezes the UI.
     * Daemon = true so a hung native call doesn't pin the JVM at exit.
     */
    private inline fun openOnDaemonThread(name: String, crossinline body: () -> Unit) {
        Thread({ runCatching { body() } }, name).apply {
            isDaemon = true
            start()
        }
    }
}
