package hivens.ui.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Keeps the logs directory from growing without end, once, at startup.
 *
 * The four logback channels rotate themselves. What did not was the per-session
 * copy of a game's output that [GameConsoleService] writes for the Logs tab, one
 * file per launch and never deleted: half a gigabyte over a few months, a third of
 * it for packs that no longer exist. Those are held to an age and then to a total
 * size, oldest first. A `.tmp` is what an interrupted compression leaves behind,
 * and nothing reads or removes it afterwards.
 *
 * Exports the player asked for, and everything logback owns, are left alone.
 * A file touched in the last minute is never removed, in case a session is
 * already being written.
 */
class LogRetention(
    private val logsDir: Path,
    private val maxAge: Duration = MAX_AGE,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(LogRetention::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { sweep() }.onFailure { log.warn("Log cleanup failed", it) }
        }
    }

    /** One pass. Returns how many files were removed and how many bytes that freed. */
    fun sweep(): Pair<Int, Long> {
        if (!Files.isDirectory(logsDir)) return 0 to 0L
        val entries = logsDir.listDirectoryEntries().filter { it.isRegularFile() }
        val t = now()
        val settled = { p: Path -> Duration.between(p.getLastModifiedTime().toInstant(), t) > IN_USE }

        var removed = 0
        var freed = 0L
        fun delete(p: Path) {
            val size = runCatching { p.fileSize() }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) {
                removed++
                freed += size
            }
        }

        // A compression that is still running writes its .tmp now; one left an
        // hour ago is not going to finish.
        entries.filter { it.name.endsWith(".tmp") && Duration.between(it.getLastModifiedTime().toInstant(), t) > STALE_TMP }
            .forEach(::delete)

        val sessions = entries
            .filter { it.name.startsWith(SESSION_PREFIX) && it.name.endsWith(".log") && settled(it) }
            .sortedBy { it.getLastModifiedTime().toMillis() }
        val kept = ArrayList<Path>()
        for (p in sessions) {
            if (Duration.between(p.getLastModifiedTime().toInstant(), t) > maxAge) delete(p) else kept.add(p)
        }
        var total = kept.sumOf { runCatching { it.fileSize() }.getOrDefault(0L) }
        for (p in kept) {
            if (total <= maxTotalBytes) break
            val size = runCatching { p.fileSize() }.getOrDefault(0L)
            delete(p)
            total -= size
        }

        if (removed > 0) log.info("Log cleanup: removed {} file(s), {} MB", removed, freed / (1024 * 1024))
        return removed to freed
    }

    companion object {
        /** What [GameConsoleService] names a session's file. */
        const val SESSION_PREFIX = "game-output-"

        val MAX_AGE: Duration = Duration.ofDays(30)
        const val MAX_TOTAL_BYTES: Long = 300L * 1024 * 1024

        private val IN_USE: Duration = Duration.ofMinutes(1)
        private val STALE_TMP: Duration = Duration.ofHours(1)
    }
}
