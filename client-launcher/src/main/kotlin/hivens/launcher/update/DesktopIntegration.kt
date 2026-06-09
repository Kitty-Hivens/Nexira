package hivens.launcher.update

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Installs a freedesktop `.desktop` entry so the running AppImage shows up in
 * the application menu (and registers the `nexira:` scheme handler). Linux +
 * AppImage only -- [isSupported] is false otherwise, and the manager hides the
 * action. Best-effort: the entry's `Exec` points at the live `$APPIMAGE`, the
 * icon references the AppImage itself (DEs that read the embedded icon show it),
 * and `update-desktop-database` is nudged but never required.
 */
class DesktopIntegration {
    private val logger = LoggerFactory.getLogger(DesktopIntegration::class.java)
    private val osName = System.getProperty("os.name", "").lowercase()

    /** Only meaningful when running as an AppImage on Linux. */
    fun isSupported(): Boolean =
        osName.contains("linux") && !System.getenv("APPIMAGE").isNullOrBlank()

    /**
     * Writes `~/.local/share/applications/dev.hivens.nexira.desktop` for the
     * current AppImage. Returns the entry path on success.
     */
    fun installEntry(): Result<Path> = runCatching {
        val appImage = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
            ?: error("Not running as an AppImage (APPIMAGE unset)")
        val home = System.getProperty("user.home") ?: error("user.home is unset")

        val appsDir = Paths.get(home, ".local", "share", "applications")
        Files.createDirectories(appsDir)
        val entry = appsDir.resolve("dev.hivens.nexira.desktop")

        Files.writeString(entry, desktopEntryContent(appImage))
        runCatching { entry.toFile().setExecutable(true) }
        // Best-effort menu refresh; absent on minimal systems, never fatal.
        runCatching { ProcessBuilder("update-desktop-database", appsDir.toString()).start() }

        logger.info("Installed desktop entry at {}", entry)
        entry
    }.onFailure { logger.warn("Failed to install desktop entry", it) }

    internal fun desktopEntryContent(appImagePath: String): String = """
        [Desktop Entry]
        Version=1.0
        Type=Application
        Name=Nexira
        GenericName=Game Launcher
        Icon=$appImagePath
        Exec="$appImagePath" %U
        StartupWMClass=Nexira
        Categories=Game;
        MimeType=x-scheme-handler/nexira;
    """.trimIndent() + "\n"
}
