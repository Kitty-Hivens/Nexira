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
        if (Files.isDirectory(target) && !target.isEmpty()) {
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

    private fun Path.isEmpty(): Boolean =
        Files.list(this).use { !it.findAny().isPresent }
}
