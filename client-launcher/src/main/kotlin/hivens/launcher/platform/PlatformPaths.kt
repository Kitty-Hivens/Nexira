package hivens.launcher.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the per-OS application data directory.
 *
 * Resolution order:
 * 1. `AURA_DATA_DIR` env var (if set and non-blank) -- universal
 *    override; spiritual equivalent of `XDG_DATA_HOME` across platforms.
 * 2. [BootstrapConf] `data-dir` key -- user-chosen override persisted
 *    via the "Move data directory" Settings UI. Bootstrap conf lives
 *    outside the data dir so the launcher can find the override before
 *    it knows where the data dir is.
 * 3. Per-OS default:
 *    - Windows: `%LOCALAPPDATA%\AuraLauncher` (separate from install dir under `%APPDATA%`)
 *    - macOS:   `~/Library/Application Support/AuraLauncher`
 *    - Linux:   `$XDG_DATA_HOME/aura-launcher` (default `~/.local/share/aura-launcher`)
 *
 * The pre-2.3 `~/.aura/` is exposed via [legacyDataDir] and only the
 * migration path should read it; production code uses [dataDir] and
 * its derivatives.
 */
class PlatformPaths(
    osName: String,
    private val home: Path,
    bootstrapDataDir: () -> Path? = { BootstrapConf.read()[BootstrapConf.KEY_DATA_DIR]?.let { Paths.get(it) } },
    env: (String) -> String?,
) {
    private val isWindows = osName.contains("windows", ignoreCase = true)
    private val isMacOs = osName.contains("mac", ignoreCase = true) ||
            osName.contains("darwin", ignoreCase = true)

    val dataDir: Path = run {
        val envOverride = env("AURA_DATA_DIR")
        if (!envOverride.isNullOrBlank()) return@run Paths.get(envOverride)

        val confOverride = bootstrapDataDir()
        if (confOverride != null) return@run confOverride

        when {
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
    }

    val logsDir: Path get() = dataDir.resolve("logs")
    val crashDir: Path get() = dataDir.resolve("crash-reports")
    val skinCacheDir: Path get() = dataDir.resolve("skin-cache")
    val clientsDir: Path get() = dataDir.resolve("clients")

    /**
     * @throws IllegalArgumentException when [assetDir] contains
     *         path-separator or traversal characters. Allowed charset
     *         is ASCII alnum + `._-`; anything else is treated as a
     *         hostile or malformed manifest and refused before
     *         [Path.resolve].
     */
    fun clientDir(assetDir: String): Path =
        clientsDir.resolve(ServerNameValidator.require(assetDir))

    /** Pre-2.3 data directory; only the migration path reads this. */
    val legacyDataDir: Path = home.resolve(".aura")

    companion object {
        fun system(): PlatformPaths = PlatformPaths(
            osName = System.getProperty("os.name", ""),
            home = Paths.get(System.getProperty("user.home", ".")),
            env = { System.getenv(it) },
        )
    }
}
