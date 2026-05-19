package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Data-dir migration from an Aura-era location to the current Nexira
 * location. Walks [PlatformPaths.legacyDataDirs] in priority order and
 * imports from the first non-empty candidate that is not already marked
 * as migrated.
 *
 * Copy semantics: source files are copied (not moved). The legacy
 * directory is left in place with a `.migrated` marker so a re-run on
 * the same installation skips it. Users can manually delete the legacy
 * directory once they've verified the migration.
 *
 * Skipped per-candidate when:
 * - the legacy directory does not exist (not relevant for this user);
 * - the marker file is already present (already migrated);
 * - the legacy directory contains nothing beyond housekeeping markers;
 * - the target directory exists and is non-empty (defensive -- never
 *   overwrite, write marker on legacy to stop revisiting).
 */
object DataDirMigration {
    private val log = LoggerFactory.getLogger(DataDirMigration::class.java)
    private const val MARKER = ".migrated"

    /**
     * Files the launcher writes to its dataDir BEFORE migration runs
     * (SingleInstance.acquire grabs the lock before
     * DataDirMigration.run, by design -- see Main.kt). Their presence
     * MUST NOT make the target look "already populated", otherwise
     * first-run migration silently skips and the legacy payload is
     * lost.
     *
     * Add new entries here whenever startup gains another
     * pre-migration housekeeping file.
     */
    private val HOUSEKEEPING = setOf(".lock", ".lock.pid", ".show", MARKER)

    fun run(paths: PlatformPaths) {
        val target = paths.dataDir
        for (legacy in paths.legacyDataDirs) {
            val marker = legacy.resolve(MARKER)

            if (!Files.isDirectory(legacy)) continue
            if (Files.exists(marker)) continue
            if (!legacy.hasUserData()) continue
            if (Files.isDirectory(target) && target.hasUserData()) {
                log.info("Target data directory {} already populated; skipping legacy import from {}", target, legacy)
                writeMarker(marker, target)
                continue
            }

            log.info("Migrating user data: {} -> {}", legacy, target)
            Files.createDirectories(target)
            copyTree(legacy, target)
            writeMarker(marker, target)
            log.info("Migration completed; legacy directory left in place at {}", legacy)
            return
        }
    }

    private fun copyTree(source: Path, dest: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                if (src == source) return@forEach
                if (src.fileName?.toString() == MARKER) return@forEach
                val rel = source.relativize(src).toString()
                val dst = dest.resolve(rel)
                runCatching {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst)
                    } else {
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            src, dst,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }.onFailure { log.warn("Failed to copy {} -> {}", src, dst, it) }
            }
        }
    }

    private fun writeMarker(marker: Path, target: Path) {
        runCatching {
            Files.writeString(marker, "Migrated to $target at ${Instant.now()}\n")
        }
    }

    /**
     * True when the directory contains anything beyond [HOUSEKEEPING].
     *
     * The launcher writes housekeeping markers (.lock / .lock.pid / .show /
     * .migrated) before migration runs -- see Main.kt's startup sequence.
     * Treating those as "user data" would cause us to skip a legitimate
     * first-run migration (the lock files from this very startup would
     * already be in place).
     */
    private fun Path.hasUserData(): Boolean =
        Files.list(this).use { stream ->
            stream.anyMatch { entry ->
                val name = entry.fileName?.toString() ?: return@anyMatch false
                name !in HOUSEKEEPING
            }
        }
}
