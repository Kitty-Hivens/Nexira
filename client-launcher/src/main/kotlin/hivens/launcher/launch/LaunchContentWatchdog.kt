package hivens.launcher.launch

import hivens.core.api.interfaces.IPackSyncService
import hivens.launcher.util.DirectorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches an instance's `mods/` from the moment the game process starts until the
 * loader can no longer be reached by anything new.
 *
 * The pre-spawn seal is the last thing that runs before `ProcessBuilder.start`, and
 * it is honest about what it can promise: it establishes what was on disk at that
 * instant. The JVM then takes seconds to come up and only then reads `mods/`. That
 * gap belonged to nobody, and it is wide enough to drop a jar into -- widened
 * further by anything that makes startup slower.
 *
 * Two mechanisms, because they fail in different ways:
 *
 *  - A listing of `mods/` every [pollMillis] is what sees content that does not
 *    survive to be walked. A jar planted before the loader scans and unlinked
 *    right after is gone by the time anything hashes the directory, but the
 *    loader has it open and running. Catching it means noticing while it is
 *    still there.
 *  - The pass at [settleMillis] is the backstop. It owes nothing to timing: it
 *    walks and hashes, so a jar dropped in and left behind is found even if
 *    every poll happened to fall the wrong side of it.
 *
 * The listing is deliberately not a [java.nio.file.WatchService]. The JDK only
 * has a native watcher on some platforms; on the rest -- macOS among them -- it
 * degrades to polling on an interval of its own choosing, measured in seconds.
 * That is wider than the entire window this guard exists to cover, so the one
 * mechanism that can see a vanished jar would be the one that quietly does
 * nothing there. Reading the directory costs a readdir and a stat per entry and
 * behaves the same on every platform.
 *
 * The deadline has only a LOWER bound that matters. Running late costs a cheated
 * session a few more seconds before it ends; running early leaves the tail of the
 * startup unwatched, which is the whole defect. So it is set well past any
 * plausible time-to-mod-scan rather than tuned.
 *
 * Reports and does not act. The caller owns the process and decides -- deleting a
 * jar the loader holds open would break the install to no purpose, since the code
 * is already running.
 */
internal class LaunchContentWatchdog(
    private val sync: IPackSyncService,
    private val clientDir: Path,
    private val expected: Map<String, String>?,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val log = LoggerFactory.getLogger(LaunchContentWatchdog::class.java)

    /**
     * Runs until the instance is found to have changed, until [settleMillis] has
     * passed, or until cancelled. Returns what was found, empty when nothing was.
     *
     * Cancellation is checked every [pollMillis], so a game that exits early ends
     * this promptly instead of holding a thread for the full settle.
     */
    suspend fun run(): List<String> = withContext(Dispatchers.IO) {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return@withContext emptyList()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMillis)
        var seen = DirectorySnapshot.of(modsDir)
        while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
            delay(pollMillis.milliseconds)
            val now = DirectorySnapshot.of(modsDir)
            if (now == seen) continue
            seen = now
            // The change is not read for what it was. What belongs under `mods/`
            // is one rule and it lives in the sync service; duplicating a filename
            // filter here is how the launch gate and the session guard would come
            // to disagree. A difference only says "look again".
            inspect().takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        if (!currentCoroutineContext().isActive) return@withContext emptyList()
        inspect()
    }

    private suspend fun inspect(): List<String> {
        val inspection = runCatching { sync.inspectRoster(clientDir, expected) }
            .onFailure { log.warn("launch watch: could not read {}: {}", clientDir.fileName, it.toString()) }
            .getOrNull()
            ?: return emptyList()
        return if (inspection.isClean) emptyList() else inspection.findings
    }

    internal companion object {
        /**
         * How long after the spawn the authoritative pass runs. Chosen to sit well
         * past the slowest plausible time from process start to the loader reading
         * `mods/` -- a large pack, a cold cache, a spinning disk -- because being
         * late here is cheap and being early defeats the guard.
         */
        const val SETTLE_MILLIS = 90_000L

        /**
         * How often `mods/` is listed. This is the resolution of the guard: a jar
         * that is both planted and unlinked inside one interval is seen by nothing,
         * so it is short enough that doing so would have to be deliberate and
         * precise. Also how fast cancellation lands.
         */
        const val POLL_MILLIS = 250L
    }
}
