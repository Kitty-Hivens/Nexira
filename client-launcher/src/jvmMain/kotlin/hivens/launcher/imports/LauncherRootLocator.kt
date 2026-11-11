package hivens.launcher.imports

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the candidate on-disk data roots for each [ForeignLauncher]. A single
 * hardcoded path is wrong: the same launcher can live in very different places
 * depending on how it was installed --
 *
 *  - **native** XDG (`$XDG_DATA_HOME` / `$XDG_CONFIG_HOME`, default `~/.local/share`),
 *  - **Flatpak** sandbox (`~/.var/app/<app-id>/{data,config}/...`), where the app's
 *    XDG dirs are redirected under its per-app tree,
 *  - **Snap** (`~/snap/<snap>/current/.local/share/...`),
 *  - the OS-native location on macOS / Windows.
 *
 * [candidates] returns every plausible root without touching the disk (pure, so
 * it is unit-testable with an injected home / env / os); [existingRoots] filters
 * to the ones that actually exist. A source then probes each existing root for
 * its marker (a `profiles/` dir, `launcher_profiles.json`, an `instances/` dir).
 *
 * Linux is covered in full; macOS / Windows carry the native path so the locator
 * is not silently Linux-only, and Flatpak/Snap simply do not apply there.
 */
class LauncherRootLocator(
    private val home: Path = Paths.get(System.getProperty("user.home", ".")),
    private val env: (String) -> String? = { System.getenv(it) },
    osName: String = System.getProperty("os.name", ""),
) {
    private val os = when {
        osName.contains("win", ignoreCase = true) -> Os.Windows
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> Os.MacOs
        else -> Os.Linux
    }

    private enum class Os { Linux, MacOs, Windows }

    private val xdgData: Path
        get() = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: home.resolve(".local").resolve("share")

    private val xdgConfig: Path
        get() = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: home.resolve(".config")

    /** All plausible data roots for [launcher], most-canonical first. Not filtered by existence. */
    fun candidates(launcher: ForeignLauncher): List<Path> = when (launcher) {
        ForeignLauncher.Vanilla -> vanilla()
        ForeignLauncher.Modrinth -> flatpakAware("com.modrinth.ModrinthApp", "ModrinthApp", "modrinth") +
            // Older builds wrote a lowercase dir; keep it as a fallback candidate.
            listOf(xdgData.resolve("modrinth-app"))
        ForeignLauncher.Prism -> flatpakAware("org.prismlauncher.PrismLauncher", "PrismLauncher", "prismlauncher")
        ForeignLauncher.Ftb -> when (os) {
            Os.Windows -> listOfNotNull(appData()?.resolve(".ftba"), home.resolve(".ftba"))
            else -> listOf(home.resolve(".ftba"))
        }
    }.distinct()

    /** [candidates] filtered to directories that exist right now. */
    fun existingRoots(launcher: ForeignLauncher): List<Path> =
        candidates(launcher).filter { runCatching { Files.isDirectory(it) }.getOrDefault(false) }

    // The vanilla / TLauncher .minecraft directory.
    private fun vanilla(): List<Path> = when (os) {
        Os.Linux -> listOf(home.resolve(".minecraft"))
        Os.MacOs -> listOf(home.resolve("Library").resolve("Application Support").resolve("minecraft"))
        Os.Windows -> listOfNotNull(appData()?.resolve(".minecraft"), home.resolve(".minecraft"))
    }

    /**
     * Native + Flatpak + Snap candidates for a launcher whose data lives in a
     * dir named [dirName] under the XDG data root. [flatpakId] is the Flatpak
     * application id; [snapName] the snap package name.
     */
    private fun flatpakAware(flatpakId: String, dirName: String, snapName: String): List<Path> = when (os) {
        Os.Linux -> listOf(
            xdgData.resolve(dirName),
            xdgConfig.resolve(dirName),
            home.resolve(".var").resolve("app").resolve(flatpakId).resolve("data").resolve(dirName),
            home.resolve(".var").resolve("app").resolve(flatpakId).resolve("config").resolve(dirName),
            home.resolve("snap").resolve(snapName).resolve("current").resolve(".local").resolve("share").resolve(dirName),
        )
        Os.MacOs -> listOf(home.resolve("Library").resolve("Application Support").resolve(dirName))
        Os.Windows -> listOfNotNull(appData()?.resolve(dirName), home.resolve("AppData").resolve("Roaming").resolve(dirName))
    }

    private fun appData(): Path? = env("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
}
