package hivens.launcher.launch

import hivens.core.api.interfaces.IPackSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

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
 *  - The watch answers immediately and, more to the point, answers about things
 *    that are no longer there. A jar planted before the loader scans and unlinked
 *    right after is gone by the time anything walks the directory, but the loader
 *    has it open and running. Only an event says it was ever there.
 *  - The pass at [settleMillis] is the backstop. A watch can miss -- it is
 *    poll-backed on macOS, it drops events under pressure, and a filesystem the
 *    instance lives on may not support it at all. The deadline pass owes nothing
 *    to any of that; it walks and hashes.
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
        var watcher: WatchService? = null
        try {
            watcher = register(modsDir)
            while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
                val key = watcher?.poll(pollMillis, TimeUnit.MILLISECONDS)
                if (key == null) {
                    // No watch to wait on: still pace the loop so cancellation and
                    // the deadline are both observed.
                    if (watcher == null) delay(pollMillis)
                    continue
                }
                // The events are not read for their contents. What belongs under
                // `mods/` is one rule and it lives in the sync service; duplicating
                // a filename filter here is how the launch gate and the session
                // guard would come to disagree. An event only says "look again".
                key.pollEvents()
                key.reset()
                inspect().takeIf { it.isNotEmpty() }?.let { return@withContext it }
            }
            if (!currentCoroutineContext().isActive) return@withContext emptyList()
            inspect()
        } finally {
            runCatching { watcher?.close() }
        }
    }

    private suspend fun inspect(): List<String> {
        val inspection = runCatching { sync.inspectRoster(clientDir, expected) }
            .onFailure { log.warn("launch watch: could not read {}: {}", clientDir.fileName, it.toString()) }
            .getOrNull()
            ?: return emptyList()
        return if (inspection.isClean) emptyList() else inspection.findings
    }

    /**
     * Null when the platform or filesystem will not give us a watch. That is a
     * degraded guard, not a broken one -- the deadline pass still runs -- so it is
     * logged and carried on from rather than failing the launch.
     */
    private fun register(modsDir: Path): WatchService? =
        runCatching {
            FileSystems.getDefault().newWatchService().also { service ->
                modsDir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
            }
        }.onFailure {
            log.warn("launch watch: no file watch on {}, falling back to the settle pass alone: {}", modsDir, it.toString())
        }.getOrNull()

    internal companion object {
        /**
         * How long after the spawn the authoritative pass runs. Chosen to sit well
         * past the slowest plausible time from process start to the loader reading
         * `mods/` -- a large pack, a cold cache, a spinning disk -- because being
         * late here is cheap and being early defeats the guard.
         */
        const val SETTLE_MILLIS = 90_000L

        /** How often the watch is asked, and therefore how fast cancellation lands. */
        const val POLL_MILLIS = 250L
    }
}
