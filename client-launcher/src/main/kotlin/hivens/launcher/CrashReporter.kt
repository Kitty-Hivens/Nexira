package hivens.launcher

import hivens.config.Branding
import hivens.launcher.platform.PlatformPaths
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JOptionPane

object CrashReporter {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Resolved at process start by [hivens.ui.MainKt]. Default to the system-derived
     * paths so a crash before init still lands in a sensible location.
     */
    @Volatile
    var paths: PlatformPaths = PlatformPaths.system()

    @Volatile
    var lastAction: String? = null

    data class CrashReport(
        val timestamp: String,
        val version: String,
        val osName: String,
        val osVersion: String,
        val osArch: String,
        val jvmVersion: String,
        val jvmVendor: String,
        val maxMemoryMb: Long,
        val lastAction: String?,
        val thread: String,
        val stackTrace: String
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
            lastAction = lastAction,
            thread = thread.name,
            stackTrace = throwable.stackTraceToString()
        )
    }

    fun saveToDisk(report: CrashReport): File {
        val crashDir = paths.crashDir.toFile()
        crashDir.mkdirs()

        val ts = formatter.format(Instant.parse(report.timestamp))
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
            appendLine(" Last action: ${report.lastAction ?: "Unknown"}")
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

        val options = arrayOf("Copy report", "Open folder", "Close")
        val choice = JOptionPane.showOptionDialog(
            null,
            message,
            "Crash Report — ${Branding.TITLE}",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options[0]
        )

        when (choice) {
            0 -> {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(reportFile.readText()), null)
            }
            1 -> {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(reportFile.parentFile)
                }
            }
        }
    }
}