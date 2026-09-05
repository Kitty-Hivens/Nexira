package hivens.launcher.instance

import hivens.launcher.util.DirectorySnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Emits whenever an instance's content folders change on disk, so a screen showing
 * them can rescan without being told.
 *
 * The point is the file manager: a player drops a jar into `mods/` and expects the
 * launcher to have noticed by the time they switch back to it. Until now the list
 * only refreshed after a change made from inside the launcher, so anything added
 * from outside stayed invisible until the screen was reopened.
 *
 * Emissions are settled, not immediate. A jar arrives over several ticks while it
 * copies, and rescanning mid-copy reads a truncated archive: the parse fails and
 * the row appears as an unreadable mod that fixes itself seconds later. So a change
 * starts a wait for the folder to stop moving, and only the quiet that follows is
 * reported. One emission covers a batch, which is also what dropping twenty files
 * at once should produce.
 */
class ContentFolderWatch(
    private val pollMillis: Long = POLL_MILLIS,
    private val settleMillis: Long = SETTLE_MILLIS,
    /**
     * Where the polling runs. Injected so a test can drive it on a scheduler with a
     * virtual clock: the waits here are the whole mechanism, and asserting on them
     * against a real clock makes the result depend on how loaded the machine is
     * rather than on the code.
     */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Cold flow of "something changed under [instanceDir]". Collect it for as long
     * as the view is on screen; cancelling the collector ends the polling.
     *
     * The first snapshot is taken before the loop, so a folder that was already
     * populated does not read as a change on subscribe.
     */
    fun changes(instanceDir: Path): Flow<Unit> = flow {
        val dirs = FOLDERS.map { instanceDir.resolve(it) }
        var seen = DirectorySnapshot.ofAll(dirs)
        while (currentCoroutineContext().isActive) {
            delay(pollMillis.milliseconds)
            val now = DirectorySnapshot.ofAll(dirs)
            if (now == seen) continue
            seen = settle(dirs, now)
            emit(Unit)
        }
    }.flowOn(dispatcher)

    /** Waits for [dirs] to stop changing, and answers with the state it settled on. */
    private suspend fun settle(dirs: List<Path>, first: Map<String, DirectorySnapshot.Mark>): Map<String, DirectorySnapshot.Mark> {
        var last = first
        while (currentCoroutineContext().isActive) {
            delay(settleMillis.milliseconds)
            val again = DirectorySnapshot.ofAll(dirs)
            if (again == last) return last
            last = again
        }
        return last
    }

    companion object {
        /** The folders [InstanceContentScanner] reads, and therefore the ones worth watching. */
        private val FOLDERS = listOf("mods", "resourcepacks", "shaderpacks")

        /**
         * How often the folders are listed. Nobody is waiting on this the way they
         * wait on a click, so it is paced for a background screen rather than for
         * latency: a readdir per folder, twice a second, against a rescan that
         * parses every archive.
         */
        const val POLL_MILLIS = 500L

        /** How long the folders must hold still before a change is reported. */
        const val SETTLE_MILLIS = 400L
    }
}
