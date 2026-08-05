package hivens.update

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Installs a freedesktop `.desktop` entry so the running AppImage shows up in
 * the application menu (and registers the `nexira:` scheme handler). Linux +
 * AppImage only -- [isSupported] is false otherwise, and the manager hides the
 * action. Best-effort: the entry's `Exec` points at the live `$APPIMAGE`, the
 * embedded icon is copied into the user icon theme so the entry can name it,
 * and `update-desktop-database` is nudged but never required.
 */
class DesktopIntegration {
    private val logger = LoggerFactory.getLogger(DesktopIntegration::class.java)

    /** Only meaningful when running as an AppImage on Linux. */
    fun isSupported(): Boolean = runningAsLinuxAppImage()

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

        // `Icon=` must resolve to an image. The AppImage is an ELF binary, so
        // pointing the icon at it leaves DEs that don't read the embedded icon
        // showing a generic placeholder. Copy the bundled PNG into the user
        // icon theme and name it; fall back to the AppImage path so the entry
        // is never worse than not installing an icon at all.
        val icon = installIcon(home).getOrElse {
            logger.debug("Embedded icon install failed; referencing the AppImage path", it)
            appImage
        }

        Files.writeString(entry, desktopEntryContent(appImage, icon))
        runCatching { entry.toFile().setExecutable(true) }
        // Best-effort menu refresh; absent on minimal systems, never fatal.
        runCatching { ProcessBuilder("update-desktop-database", appsDir.toString()).start() }

        logger.info("Installed desktop entry at {}", entry)
        entry
    }.onFailure { logger.warn("Failed to install desktop entry", it) }

    /**
     * Copies the AppImage's embedded icon (from the live `$APPDIR` mount) into
     * the user's hicolor theme as `nexira.png`, bucketed by the icon's own
     * pixel size, and returns the theme icon name for the entry's `Icon=` key.
     */
    private fun installIcon(home: String): Result<String> = runCatching {
        val appDir = System.getenv("APPDIR")?.takeIf { it.isNotBlank() }
            ?: error("APPDIR unset")
        val source = sequenceOf(ICON_NAME + ".png", ".DirIcon")
            .map { Paths.get(appDir, it) }
            .firstOrNull { Files.isRegularFile(it) }
            ?: error("No embedded icon found under APPDIR")
        installIconInto(source, Paths.get(home, ".local", "share", "icons"))
    }

    /**
     * Copies [source] into `<iconsRoot>/hicolor/<w>x<h>/apps/<ICON_NAME>.png`
     * and returns [ICON_NAME]. Split out from environment lookup so it is
     * testable against a temp tree. Requires a readable PNG so the size bucket
     * is real, not guessed.
     */
    internal fun installIconInto(source: Path, iconsRoot: Path): String {
        val (w, h) = pngSize(source) ?: error("Embedded icon is not a readable PNG: $source")
        val dir = iconsRoot.resolve("hicolor").resolve("${w}x${h}").resolve("apps")
        Files.createDirectories(dir)
        Files.copy(source, dir.resolve("$ICON_NAME.png"), StandardCopyOption.REPLACE_EXISTING)
        return ICON_NAME
    }

    /** Width/height read from a PNG's IHDR, or null when [path] is not a PNG. */
    internal fun pngSize(path: Path): Pair<Int, Int>? = runCatching {
        Files.newInputStream(path).use { ins ->
            val head = ins.readNBytes(24)
            if (head.size < 24 || !head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
            // 8-byte signature, 4-byte IHDR length, 4-byte "IHDR" type, then the
            // big-endian width (offset 16) and height (offset 20).
            fun be(off: Int): Int =
                ((head[off].toInt() and 0xFF) shl 24) or
                    ((head[off + 1].toInt() and 0xFF) shl 16) or
                    ((head[off + 2].toInt() and 0xFF) shl 8) or
                    (head[off + 3].toInt() and 0xFF)
            be(16) to be(20)
        }
    }.getOrNull()

    internal fun desktopEntryContent(appImagePath: String, icon: String): String = """
        [Desktop Entry]
        Version=1.0
        Type=Application
        Name=Nexira
        GenericName=Game Launcher
        Icon=$icon
        Exec="$appImagePath" %U
        StartupWMClass=Nexira
        Categories=Game;
        MimeType=x-scheme-handler/nexira;
    """.trimIndent() + "\n"

    private companion object {
        const val ICON_NAME = "nexira"
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
