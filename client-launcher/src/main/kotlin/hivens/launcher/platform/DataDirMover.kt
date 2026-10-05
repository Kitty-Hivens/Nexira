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
 *     calls [applyPending]. It marks the target as a move in progress
 *     ([IN_PROGRESS_MARKER]), copies the source tree into it, verifies the
 *     copy, drops the marker, commits the new path as `data-dir` and clears
 *     the pending markers, and only then deletes the source.
 *  4. If the copy fails partway through, the pending markers stay set and
 *     the target keeps its marker, so the next start knows the contents are
 *     its own unfinished copy and starts it again rather than refusing it.
 *  5. The source is deleted after the commit, because on Windows this very
 *     process holds files in it open (the single-instance lock, the log), so
 *     the delete can fail partway. Whatever it leaves is recorded as
 *     `data-dir-stale-source` and deleted at the next start, when nothing
 *     holds it any more. Deleting first used to leave a half-deleted source
 *     as the data dir and the complete copy unused.
 *
 * Failure modes handled:
 *  - Target doesn't exist -> created
 *  - Target is the source -> no-op, clears pending
 *  - Target already has contents that are not an unfinished copy of ours ->
 *    refused; pending cleared with error log. User must pick an empty dir
 *    or merge manually.
 *  - Target is inside source -> refused (would recurse during copy)
 *  - I/O failure or a count mismatch mid-copy -> what was copied is
 *    removed, source intact, pending kept (retry on next start)
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
        conf[BootstrapConf.KEY_STALE_SOURCE]?.let { removeStaleSource(it, conf[BootstrapConf.KEY_DATA_DIR], confFile) }
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

        // Target already populated (not just the dir, but contents): refuse, unless
        // what is there is this move's own unfinished copy, which is started again.
        val ownPartialCopy = Files.isRegularFile(target.resolve(IN_PROGRESS_MARKER))
        if (Files.exists(target) && hasContents(target) && !ownPartialCopy) {
            log.error(
                "target {} already has contents -- refusing to overwrite. " +
                    "Clearing pending markers; user must pick an empty dir or merge manually.",
                target,
            )
            clearPending(confFile)
            return
        }

        val srcCount = try {
            if (ownPartialCopy) {
                log.info("target {} holds an unfinished copy from an earlier start, copying again", target)
                clearCopy(target)
            }
            Files.createDirectories(target)
            Files.writeString(target.resolve(IN_PROGRESS_MARKER), sourceStr)
            copyTree(source, target)
            // Verify file count matches before anything is committed -- cheap sanity.
            val srcCount = countFiles(source)
            val dstCount = countFiles(target) - 1 // the marker
            if (srcCount != dstCount) {
                log.error("copy verification failed: source={} files, target={} -- removing the copy, retry next start", srcCount, dstCount)
                clearCopy(target)
                return
            }
            Files.delete(target.resolve(IN_PROGRESS_MARKER))
            srcCount
        } catch (e: Exception) {
            log.error("applyPending() failed mid-copy -- removing the copy, pending markers retained for retry: {}", e.message, e)
            runCatching { clearCopy(target) }
                .onFailure { log.warn("could not remove the unfinished copy in {}; the next start recognises it by its marker", target, it) }
            return
        }

        commit(targetStr, confFile, staleSource = sourceStr)
        log.info("Data-dir move committed: {} files relocated", srcCount)
        removeStaleSource(sourceStr, targetStr, confFile)
    }

    /**
     * Deletes what is left of the old data dir after a committed move, and stops
     * remembering it once it is gone. Best effort: a file this process still holds
     * keeps it for the next start. Never the directory in use, whatever the conf says.
     */
    private fun removeStaleSource(stale: String, dataDir: String?, confFile: Path) {
        val path = Paths.get(stale)
        if (dataDir != null && path.normalize() == Paths.get(dataDir).normalize()) {
            BootstrapConf.update(confFile) { it.remove(BootstrapConf.KEY_STALE_SOURCE) }
            return
        }
        runCatching { deleteTree(path) }
            .onFailure { log.warn("old data dir {} not fully removed, trying again next start: {}", path, it.message) }
        if (!Files.exists(path)) {
            BootstrapConf.update(confFile) { it.remove(BootstrapConf.KEY_STALE_SOURCE) }
        }
    }

    /** Empties [target] of what a copy put there, keeping the directory itself. */
    private fun clearCopy(target: Path) {
        if (!Files.exists(target)) return
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).filter { it != target }.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun commit(newDataDir: String, confFile: Path, staleSource: String? = null) {
        BootstrapConf.update(confFile) { conf ->
            conf[BootstrapConf.KEY_DATA_DIR] = newDataDir
            conf.remove(BootstrapConf.KEY_PENDING_SOURCE)
            conf.remove(BootstrapConf.KEY_PENDING_TARGET)
            if (staleSource != null) conf[BootstrapConf.KEY_STALE_SOURCE] = staleSource
        }
    }

    /**
     * Written into the target before the copy starts and removed once it has been
     * verified. A target holding it is this move's own unfinished copy, which a
     * retry may replace, rather than somebody's directory, which it must not.
     */
    internal const val IN_PROGRESS_MARKER = ".nexira-move-in-progress"

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
                if (isProcessFile(rel)) return@forEach
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
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !isProcessFile(root.relativize(it)) }
                .collect(Collectors.counting())
        }

    /**
     * A file that belongs to the running process rather than to the data: the
     * single-instance lock, its pid and the raise signal, at the top of the
     * directory. Not copied, since the next start makes its own in the new place,
     * and not copyable on Windows anyway: this process holds a mandatory lock on
     * `.lock`, and a read of it fails there even from the same process, which failed
     * the whole copy on every start.
     */
    private fun isProcessFile(rel: Path): Boolean =
        rel.nameCount == 1 && rel.fileName.toString() in PROCESS_FILES

    private val PROCESS_FILES = setOf(".lock", ".lock.pid", ".show")
}
