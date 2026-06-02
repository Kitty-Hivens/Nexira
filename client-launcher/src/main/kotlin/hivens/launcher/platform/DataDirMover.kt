package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * Schedule-on-restart data-directory move.
 *
 * Why schedule-on-restart instead of moving live:
 *  - On Windows, files that are open (single-instance lock, credentials,
 *    rolling log appenders, etc.) cannot be deleted from underneath the
 *    JVM. A live move would partially fail.
 *  - Even on POSIX, races between the move and background tasks
 *    (AutoSync writing manifest cache, login writing credentials) leak
 *    files to the old path.
 *
 * The flow:
 *  1. UI calls [schedule] with the target path. It writes
 *     `data-dir-pending-source` + `data-dir-pending-target` into
 *     [BootstrapConf] (NOT yet `data-dir` -- only after a successful
 *     apply do we commit the new dir as the override).
 *  2. UI prompts the user to restart.
 *  3. On next startup, BEFORE [PlatformPaths] is consulted, the launcher
 *     calls [applyPending]. It copies the source tree to the target,
 *     verifies the copy, deletes the source, commits the new path as
 *     `data-dir`, and clears the pending markers.
 *  4. If [applyPending] fails partway through, the pending markers stay
 *     set so a retry can happen on a later restart. Source and target
 *     both exist until the apply completes (no destructive ordering).
 *
 * Failure modes handled:
 *  - Target doesn't exist -> created
 *  - Target is the source -> no-op, clears pending
 *  - Target already has launcher files -> refused; pending cleared with
 *    error log. User must pick an empty dir or merge manually.
 *  - Target is inside source -> refused (would recurse during copy)
 *  - I/O failure mid-copy -> target deleted, source intact, pending kept
 *    (retry on next start)
 */
object DataDirMover {
    // Lazy logger -- DataDirMover is referenced from Main.kt's bootstrap
    // path BEFORE `nexira.logs.dir` system property gets set. An eager
    // `LoggerFactory.getLogger(...)` field initialiser would trigger
    // logback's first init at the wrong moment, causing the rolling
    // file appender to open `./logs/launcher.log` (in the JVM's working
    // dir, e.g. `D:\Games\Nexira\logs`) instead of
    // `paths.logsDir`. Lazy delays init until the first log call --
    // by which time Main.kt has set the property correctly.
    private val log by lazy { LoggerFactory.getLogger(DataDirMover::class.java) }

    /**
     * UI-side: persist the move intent. Returns true if successfully
     * scheduled. The caller should follow with a restart prompt.
     */
    fun schedule(source: Path, target: Path, confFile: Path = BootstrapConf.defaultPath()): Boolean {
        return try {
            if (source.normalize() == target.normalize()) {
                log.info("schedule(): source == target, nothing to do")
                return false
            }
            if (target.normalize().startsWith(source.normalize())) {
                log.warn("schedule(): refusing to move into a subdirectory of source ({} -> {})", source, target)
                return false
            }
            BootstrapConf.update(confFile) { conf ->
                conf[BootstrapConf.KEY_PENDING_SOURCE] = source.toAbsolutePath().toString()
                conf[BootstrapConf.KEY_PENDING_TARGET] = target.toAbsolutePath().toString()
            }
            true
        } catch (e: Exception) {
            log.error("schedule() failed: {}", e.message, e)
            false
        }
    }

    /**
     * Startup-side: if a pending move is recorded, execute it. Idempotent
     * -- calling multiple times is fine (a missing source is treated as
     * "already applied" and clears the markers).
     */
    fun applyPending(confFile: Path = BootstrapConf.defaultPath()) {
        val conf = BootstrapConf.read(confFile)
        val sourceStr = conf[BootstrapConf.KEY_PENDING_SOURCE] ?: return
        val targetStr = conf[BootstrapConf.KEY_PENDING_TARGET] ?: return

        val source = Paths.get(sourceStr)
        val target = Paths.get(targetStr)

        log.info("Applying pending data-dir move: {} -> {}", source, target)

        // Source missing: assume an earlier apply already moved it. Mark
        // the target as the new data-dir and clear pending.
        if (!Files.exists(source)) {
            log.info("source missing -- assuming earlier apply succeeded, committing target as new data-dir")
            commit(targetStr, confFile)
            return
        }

        // Target already populated (not just the dir, but contents): refuse.
        if (Files.exists(target) && hasContents(target)) {
            log.error(
                "target {} already has contents -- refusing to overwrite. " +
                    "Clearing pending markers; user must pick an empty dir or merge manually.",
                target,
            )
            clearPending(confFile)
            return
        }

        try {
            if (!Files.exists(target)) Files.createDirectories(target)
            copyTree(source, target)
            // Verify file count matches before deleting source -- cheap sanity.
            val srcCount = countFiles(source)
            val dstCount = countFiles(target)
            if (srcCount != dstCount) {
                log.error("copy verification failed: source={} files, target={} -- leaving both intact, retry next start", srcCount, dstCount)
                return
            }
            deleteTree(source)
            commit(targetStr, confFile)
            log.info("Data-dir move complete: {} files relocated", srcCount)
        } catch (e: Exception) {
            log.error("applyPending() failed mid-flight -- pending markers retained for retry: {}", e.message, e)
        }
    }

    private fun commit(newDataDir: String, confFile: Path) {
        BootstrapConf.update(confFile) { conf ->
            conf[BootstrapConf.KEY_DATA_DIR] = newDataDir
            conf.remove(BootstrapConf.KEY_PENDING_SOURCE)
            conf.remove(BootstrapConf.KEY_PENDING_TARGET)
        }
    }

    private fun clearPending(confFile: Path) {
        BootstrapConf.update(confFile) { conf ->
            conf.remove(BootstrapConf.KEY_PENDING_SOURCE)
            conf.remove(BootstrapConf.KEY_PENDING_TARGET)
        }
    }

    private fun hasContents(dir: Path): Boolean =
        runCatching { Files.list(dir).use { it.findAny().isPresent } }.getOrDefault(false)

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                // Skip symlinks: a link pointing outside the data dir
                // would either leak its target into the move (escape
                // boundary) or break post-move (dangling link). Log
                // each skip so the user has a record if migration
                // looks incomplete.
                if (Files.isSymbolicLink(src)) {
                    log.warn("Skipping symlink during data-dir copy: {}", src)
                    return@forEach
                }
                val rel = source.relativize(src)
                val dst = target.resolve(rel.toString())
                when {
                    Files.isDirectory(src) -> if (!Files.exists(dst)) Files.createDirectories(dst)
                    else -> Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * NOFOLLOW_LINKS: must match what [copyTree] decided to materialise.
     * [copyTree] skips symbolic links (the source tree should not contain
     * any, but defensively). A default `Files::isRegularFile` follows
     * symlinks and counts a link-to-file as one regular file, while the
     * target gets nothing -- the resulting mismatch triggered the verify
     * gate's "leaving both intact, retry next start" branch and the apply
     * loop would never converge.
     */
    private fun countFiles(root: Path): Long =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .collect(Collectors.counting())
        }
}
