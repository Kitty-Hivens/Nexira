package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Flat key=value config that lives outside the data directory so the
 * launcher can find user-specific overrides before it knows where the
 * data directory is. Used by [PlatformPaths] to honour a user-chosen
 * data dir without requiring `NEXIRA_DATA_DIR` on every launch, and by
 * [DataDirMover] to schedule pending moves that apply at next startup.
 *
 * Path: `<user.home>/.nexira.conf`. Same flat location on every
 * platform -- debuggable by `cat` / Notepad, no XDG hunting, no
 * Preferences API quirks.
 *
 * Aura compatibility: when `.nexira.conf` does not exist but
 * `.aura-launcher.conf` does, [read] transparently falls back to the
 * legacy file so an Aura-era custom data-dir keeps working without
 * any user action. The first [write] / [update] lands in the new
 * file; the legacy is left in place so a downgrade to Aura doesn't
 * lose the override.
 *
 * Recognized keys:
 *   - `data-dir`                -- absolute path of user-chosen data dir
 *   - `data-dir-pending-source` -- set by UI when scheduling a move
 *   - `data-dir-pending-target` -- set by UI when scheduling a move
 *
 * Lines that don't match `key=value` are ignored on read; lines
 * starting with `#` are also dropped on read. The writer preserves
 * only known keys (no comment passthrough), so a hand-added `#`-line
 * survives until the next launcher-side write and then disappears.
 *
 * Concurrency: every operation is file-system-atomic (`Files.writeString`
 * is atomic on POSIX, atomic-on-rename on Windows). The launcher only
 * writes during user-initiated UI actions or at startup, so no
 * concurrent-writer scenario.
 */
object BootstrapConf {
    // Lazy logger -- BootstrapConf is touched during Main.kt's
    // pre-logger bootstrap. Eager init would open the rolling file at
    // the wrong path. See DataDirMover.kt for the long-form rationale.
    private val log by lazy { LoggerFactory.getLogger(BootstrapConf::class.java) }

    const val KEY_DATA_DIR = "data-dir"
    const val KEY_PENDING_SOURCE = "data-dir-pending-source"
    const val KEY_PENDING_TARGET = "data-dir-pending-target"

    /** Default location -- overridable in tests via [read] / [write] / [update] params. */
    fun defaultPath(): Path = Paths.get(System.getProperty("user.home", "."), ".nexira.conf")

    /** Aura-era location read as a transparent fallback when the modern path is absent. */
    private fun legacyDefaultPath(): Path = Paths.get(System.getProperty("user.home", "."), ".aura-launcher.conf")

    fun read(file: Path = defaultPath()): Map<String, String> {
        val effective = when {
            Files.exists(file) -> file
            file == defaultPath() && Files.exists(legacyDefaultPath()) -> legacyDefaultPath()
            else -> return emptyMap()
        }
        return try {
            Files.readAllLines(effective)
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim()
                }
                .toMap()
        } catch (e: Exception) {
            log.warn("Failed to read bootstrap conf {}: {}", effective, e.message)
            emptyMap()
        }
    }

    fun write(values: Map<String, String>, file: Path = defaultPath()) {
        try {
            if (file.parent != null) Files.createDirectories(file.parent)
            val text = values
                .filterValues { it.isNotBlank() }
                .entries
                .sortedBy { it.key }
                .joinToString(separator = System.lineSeparator()) { (k, v) -> "$k=$v" }
            Files.writeString(file, text + System.lineSeparator())
        } catch (e: Exception) {
            log.warn("Failed to write bootstrap conf {}: {}", file, e.message)
        }
    }

    /** Read, mutate, write back. */
    fun update(file: Path = defaultPath(), block: (MutableMap<String, String>) -> Unit) {
        val current = read(file).toMutableMap()
        block(current)
        write(current, file)
    }
}
