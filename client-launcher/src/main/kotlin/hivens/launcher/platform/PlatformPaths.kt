package hivens.launcher.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the per-OS application data directory.
 *
 * - Windows: %LOCALAPPDATA%\AuraLauncher (kept separate from the install dir under %APPDATA%)
 * - macOS:   ~/Library/Application Support/AuraLauncher
 * - Linux:   $XDG_DATA_HOME/aura-launcher  (default ~/.local/share/aura-launcher)
 *
 * The pre-2.3 directory ~/.aura/ is exposed via [legacyDataDir] and is read only
 * by the migration path; production code should use [dataDir] and its derivatives.
 */
class PlatformPaths(
    osName: String,
    private val home: Path,
    env: (String) -> String?
) {
    private val isWindows = osName.contains("windows", ignoreCase = true)
    private val isMacOs = osName.contains("mac", ignoreCase = true) ||
            osName.contains("darwin", ignoreCase = true)

    val dataDir: Path = when {
        isWindows -> {
            val localAppData = env("LOCALAPPDATA")
                ?: home.resolve("AppData").resolve("Local").toString()
            Paths.get(localAppData, "AuraLauncher")
        }
        isMacOs -> home.resolve("Library").resolve("Application Support").resolve("AuraLauncher")
        else -> {
            val xdg = env("XDG_DATA_HOME")
                ?: home.resolve(".local").resolve("share").toString()
            Paths.get(xdg, "aura-launcher")
        }
    }

    val logsDir: Path get() = dataDir.resolve("logs")
    val crashDir: Path get() = dataDir.resolve("crash-reports")
    val skinCacheDir: Path get() = dataDir.resolve("skin-cache")
    val clientsDir: Path get() = dataDir.resolve("clients")

    fun clientDir(assetDir: String): Path = clientsDir.resolve(assetDir)

    /** Pre-2.3 data directory; only the migration path should read this. */
    val legacyDataDir: Path = home.resolve(".aura")

    companion object {
        fun system(): PlatformPaths = PlatformPaths(
            osName = System.getProperty("os.name", ""),
            home = Paths.get(System.getProperty("user.home", ".")),
            env = { System.getenv(it) }
        )
    }
}
