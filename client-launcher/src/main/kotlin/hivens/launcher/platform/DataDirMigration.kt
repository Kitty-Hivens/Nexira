package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * One-shot migration of user data from ~/.aura/ to the platform-correct directory.
 *
 * Copies (does not move) — the legacy directory is left in place so the user can
 * verify the migration before deleting it manually. A marker file is written into
 * the legacy directory to prevent re-running.
 *
 * Skipped when:
 * - the legacy directory does not exist (clean install);
 * - the marker file is already present (already migrated);
 * - the target directory exists and is non-empty (defensive — never overwrite).
 */
object DataDirMigration {
    private val log = LoggerFactory.getLogger(DataDirMigration::class.java)
    private const val MARKER = ".migrated"

    fun run(paths: PlatformPaths) {
        val legacy = paths.legacyDataDir
        val target = paths.dataDir
        val marker = legacy.resolve(MARKER)

        if (!Files.isDirectory(legacy)) return
        if (Files.exists(marker)) return
        if (Files.isDirectory(target) && target.hasUserData()) {
            log.info("Target data directory {} already populated; skipping legacy import", target)
            writeMarker(marker, target)
            return
        }

        log.info("Migrating user data: {} -> {}", legacy, target)
        Files.createDirectories(target)
        copyTree(legacy, target)
        writeMarker(marker, target)
        log.info("Migration completed; legacy directory left in place at {}", legacy)
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
     * True when the directory contains anything beyond housekeeping markers.
     *
     * The launcher writes `.lock` (single-instance file lock) and `.show`
     * (cross-process "raise the existing instance" signal) into the data
     * directory before migration runs — see Main.kt's startup sequence.
     * `.migrated` lands in the legacy directory, but is filtered here too
     * for symmetry. Treating those as "user data" would cause us to skip
     * a legitimate first-run migration on the second launch (the .lock
     * from the first run would already be in place).
     */
    private fun Path.hasUserData(): Boolean =
        Files.list(this).use { stream ->
            stream.anyMatch { entry ->
                val name = entry.fileName?.toString() ?: return@anyMatch false
                name != ".lock" && name != ".show" && name != MARKER
            }
        }
}
