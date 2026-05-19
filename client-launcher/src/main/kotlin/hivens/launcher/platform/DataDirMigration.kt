package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Data-dir migration from an Aura-era location to the current Nexira
 * location.
 *
 * Two entry points:
 *   * [detect] -- non-mutating scan that returns the legacy directory
 *     to migrate from (or null when nothing needs migration). The
 *     mandatory migration UI in `Main.kt` uses this to decide whether
 *     to render the migration screen at launch.
 *   * [migrate] -- performs the copy with per-file progress callbacks
 *     so the UI can render a determinate progress bar.
 *
 * Copy semantics: source files are copied (not moved). The legacy
 * directory is left in place with a `.migrated` marker so a re-run on
 * the same installation skips it. Users can manually delete the
 * legacy directory once they've verified the migration.
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
     * (SingleInstance.acquire grabs the lock before the migration
     * decision, by design -- see Main.kt). Their presence MUST NOT
     * make the target look "already populated", otherwise the
     * first-run migration silently skips and the legacy payload is
     * lost.
     */
    private val HOUSEKEEPING = setOf(".lock", ".lock.pid", ".show", MARKER)

    /**
     * Pending migration source returned by [detect]. [totalBytes] and
     * [fileCount] are scanned upfront so the UI can render a useful
     * "x of y bytes / n files" progress without re-walking the tree
     * during the copy phase.
     */
    data class Source(val path: Path, val totalBytes: Long, val fileCount: Int)

    /**
     * Scans [PlatformPaths.legacyDataDirs] in priority order. Returns
     * the first non-empty, non-marked candidate, or null when no
     * migration is needed (clean install OR all legacy dirs already
     * migrated OR target is already populated). Marker is written
     * defensively when target is populated, so the same candidate
     * isn't revisited on subsequent launches.
     */
    fun detect(paths: PlatformPaths): Source? {
        val target = paths.dataDir
        for (legacy in paths.legacyDataDirs) {
            val marker = legacy.resolve(MARKER)
            if (!Files.isDirectory(legacy)) continue
            if (Files.exists(marker)) continue
            if (!legacy.hasUserData()) continue
            if (Files.isDirectory(target) && target.hasUserData()) {
                log.info("Target {} already populated; marking legacy {} as migrated", target, legacy)
                writeMarker(marker, target)
                continue
            }
            val (bytes, files) = scanSize(legacy)
            return Source(legacy, bytes, files)
        }
        return null
    }

    /**
     * Copies [source] to [target] file-by-file, invoking [onProgress]
     * after each file with cumulative bytes copied + the file that was
     * just written. Writes [MARKER] inside the legacy dir on
     * completion so subsequent launches skip it. Per-file copy
     * failures (locked files, permission errors) are logged and
     * skipped -- the overall migration succeeds as long as no
     * unrecoverable error breaks the walk.
     */
    fun migrate(
        source: Source,
        target: Path,
        onProgress: (bytesDone: Long, currentFile: Path) -> Unit = { _, _ -> },
    ): Result<Unit> = runCatching {
        Files.createDirectories(target)
        var done = 0L
        Files.walk(source.path).use { stream ->
            stream.forEach { src ->
                if (src == source.path) return@forEach
                if (src.fileName?.toString() == MARKER) return@forEach
                val rel = source.path.relativize(src).toString()
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    runCatching { Files.createDirectories(dst) }
                        .onFailure { log.warn("Failed to mkdir {}: {}", dst, it.message) }
                } else {
                    runCatching {
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            src, dst,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.onFailure { log.warn("Skipping {}: {}", src, it.message) }
                    done += runCatching { Files.size(src) }.getOrDefault(0L)
                    onProgress(done, src)
                }
            }
        }
        writeMarker(source.path.resolve(MARKER), target)
        log.info("Migration completed: {} -> {} ({} files)", source.path, target, source.fileCount)
    }

    /**
     * Convenience: detect + migrate in one call. Returns the [Source]
     * that was migrated (or null when no migration was needed).
     * Test-oriented; production code uses the split [detect] /
     * [migrate] pair so the UI layer can drive progress.
     */
    fun run(paths: PlatformPaths): Source? {
        val source = detect(paths) ?: return null
        migrate(source, paths.dataDir).onFailure {
            log.error("Migration from {} to {} failed", source.path, paths.dataDir, it)
        }
        return source
    }

    private fun scanSize(dir: Path): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        Files.walk(dir).use { stream ->
            stream.forEach { p ->
                if (Files.isDirectory(p)) return@forEach
                if (p.fileName?.toString() == MARKER) return@forEach
                bytes += runCatching { Files.size(p) }.getOrDefault(0L)
                count++
            }
        }
        return bytes to count
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
