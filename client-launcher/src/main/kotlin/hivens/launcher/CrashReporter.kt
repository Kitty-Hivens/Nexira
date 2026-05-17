package hivens.launcher

import hivens.config.Branding
import hivens.core.diag.ActionRing
import hivens.launcher.diag.IssueReporter
import hivens.launcher.platform.PlatformPaths
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JOptionPane

/**
 * Builds and persists crash reports, and surfaces the post-crash dialog
 * that lets the user open the report in their file manager, copy it,
 * or pre-fill a GitHub Issue with it.
 *
 * Constructor-injected [paths] (rather than a mutable static field
 * resolved from `PlatformPaths.system()`) so all the other Koin-wired
 * components and this one share one `PlatformPaths` instance --
 * a mid-session migration via [hivens.launcher.platform.DataDirMover] would
 * otherwise leave crash reports landing in the old directory while
 * everything else writes to the new one.
 *
 * The default uncaught-exception handler in `Main.kt` constructs an
 * instance directly because it runs before Koin starts; the same type
 * is also registered as a Koin singleton so future components can
 * inject it through DI.
 */
class CrashReporter(
    private val paths: PlatformPaths,
) {
    data class CrashReport(
        val timestamp: String,
        val version: String,
        val osName: String,
        val osVersion: String,
        val osArch: String,
        val jvmVersion: String,
        val jvmVendor: String,
        val maxMemoryMb: Long,
        /** Snapshot of [ActionRing] at crash time, oldest-first. */
        val actions: List<ActionRing.Entry>,
        val thread: String,
        val stackTrace: String,
    )

    fun generate(throwable: Throwable, thread: Thread): CrashReport {
        val rt = Runtime.getRuntime()
        return CrashReport(
            timestamp = Instant.now().toString(),
            version = Branding.VERSION,
            osName = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            osArch = System.getProperty("os.arch"),
            jvmVersion = System.getProperty("java.version"),
            jvmVendor = System.getProperty("java.vendor"),
            maxMemoryMb = rt.maxMemory() / 1_000_000,
            actions = ActionRing.snapshot(),
            thread = thread.name,
            stackTrace = throwable.stackTraceToString(),
        )
    }

    fun saveToDisk(report: CrashReport): File {
        val crashDir = paths.crashDir.toFile()
        crashDir.mkdirs()

        val ts = FILENAME_FMT.format(Instant.parse(report.timestamp))
        val file = File(crashDir, "crash-$ts.txt")

        file.writeText(buildString {
            appendLine("===================================")
            appendLine(" Aura Launcher Crash Report")
            appendLine("===================================")
            appendLine(" Generated : ${report.timestamp}")
            appendLine(" Version   : ${report.version}")
            appendLine(" OS        : ${report.osName} ${report.osVersion} (${report.osArch})")
            appendLine(" Java      : ${report.jvmVersion} (${report.jvmVendor})")
            appendLine(" Max RAM   : ${report.maxMemoryMb} MB")
            appendLine(" Thread    : ${report.thread}")
            appendLine()
            appendLine(" Recent actions (oldest first):")
            if (report.actions.isEmpty()) {
                appendLine("   (none recorded)")
            } else {
                report.actions.forEach { entry ->
                    appendLine("   [${ENTRY_TIME_FMT.format(entry.timestamp)}] ${entry.text}")
                }
            }
            appendLine()
            appendLine(" Stack Trace:")
            appendLine(report.stackTrace)
            appendLine("===================================")
        })

        return file
    }

    fun showCrashDialog(report: CrashReport, reportFile: File) {
        val message = """
            Aura Launcher quit unexpectedly.

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
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI(IssueReporter.crashIssueUrl(report)))
                    }
                }
            }
            1 -> {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(reportFile.readText()), null)
            }
            2 -> {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(reportFile.parentFile)
                }
            }
        }
    }

    companion object {
        private val FILENAME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneId.systemDefault())
        private val ENTRY_TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
