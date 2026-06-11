package hivens.launcher.platform

import hivens.core.platform.Platform
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the per-OS application data directory.
 *
 * Resolution order:
 * 1. `NEXIRA_DATA_DIR` env var (if set and non-blank) -- universal
 *    override; spiritual equivalent of `XDG_DATA_HOME` across platforms.
 * 2. [BootstrapConf] `data-dir` key -- user-chosen override persisted
 *    via the "Move data directory" Settings UI. Bootstrap conf lives
 *    outside the data dir so the launcher can find the override before
 *    it knows where the data dir is.
 * 3. Per-OS default:
 *    - Windows: `%LOCALAPPDATA%\Nexira` (separate from install dir under `%APPDATA%`)
 *    - macOS:   `~/Library/Application Support/Nexira`
 *    - Linux:   `$XDG_DATA_HOME/nexira` (default `~/.local/share/nexira`)
 *
 * Historical layouts are exposed via [legacyDataDirs] in priority order
 * (most-recent first) for the migration path. Production code uses
 * [dataDir] and its derivatives.
 */
class PlatformPaths(
    osName: String,
    private val home: Path,
    bootstrapDataDir: () -> Path? = { BootstrapConf.read()[BootstrapConf.KEY_DATA_DIR]?.let { Paths.get(it) } },
    private val env: (String) -> String?,
) {
    private val platform = Platform.classify(osName)
    private val isWindows = platform == Platform.WINDOWS
    private val isMacOs = platform == Platform.MACOS

    val dataDir: Path = run {
        val envOverride = env("NEXIRA_DATA_DIR")
        if (!envOverride.isNullOrBlank()) return@run Paths.get(envOverride)

        val confOverride = bootstrapDataDir()
        if (confOverride != null) return@run confOverride

        defaultDataDir(winMacName = "Nexira", linuxName = "nexira")
    }

    val logsDir: Path get() = dataDir.resolve("logs")
    val crashDir: Path get() = dataDir.resolve("crash-reports")
    val skinCacheDir: Path get() = dataDir.resolve("skin-cache")
    val clientsDir: Path get() = dataDir.resolve("clients")

    /**
     * Shared canonical-runtime roots, deliberately OUTSIDE any single
     * instance so libraries (maven layout) and game assets (vanilla
     * `indexes/` + content-addressed `objects/`) dedupe across every
     * pack of the same Minecraft version instead of being re-downloaded
     * per instance. Populated from official Mojang/Forge CDNs.
     */
    val librariesDir: Path get() = dataDir.resolve("libraries")
    val assetsDir: Path get() = dataDir.resolve("assets")

    /**
     * @throws IllegalArgumentException when [assetDir] contains
     *         path-separator or traversal characters. Allowed charset
     *         is ASCII alnum + `._-`; anything else is treated as a
     *         hostile or malformed manifest and refused before
     *         [Path.resolve].
     */
    fun clientDir(assetDir: String): Path =
        clientsDir.resolve(ServerNameValidator.require(assetDir))

    /**
     * Historical data directories in priority order, walked by the
     * migration UI on Nexira's first launch:
     *   1. Aura-era default (`AuraLauncher` Win/macOS / `aura-launcher`
     *      Linux) -- the layout shipped throughout the Aura 2.x line.
     *   2. Pre-2.3 (`~/.aura`) -- earliest layout, before per-OS
     *      standard locations were adopted.
     *
     * Only the migration path reads these. Production code resolves
     * everything through [dataDir].
     */
    val legacyDataDirs: List<Path> by lazy {
        listOf(
            defaultDataDir(winMacName = "AuraLauncher", linuxName = "aura-launcher"),
            home.resolve(".aura"),
        )
    }

    private fun defaultDataDir(winMacName: String, linuxName: String): Path = when {
        isWindows -> {
            val localAppData = env("LOCALAPPDATA")
                ?: home.resolve("AppData").resolve("Local").toString()
            Paths.get(localAppData, winMacName)
        }
        isMacOs -> home.resolve("Library").resolve("Application Support").resolve(winMacName)
        else -> {
            val xdg = env("XDG_DATA_HOME")
                ?: home.resolve(".local").resolve("share").toString()
            Paths.get(xdg, linuxName)
        }
    }

    companion object {
        fun system(): PlatformPaths = PlatformPaths(
            osName = System.getProperty("os.name", ""),
            home = Paths.get(System.getProperty("user.home", ".")),
            env = { System.getenv(it) },
        )
    }
}
