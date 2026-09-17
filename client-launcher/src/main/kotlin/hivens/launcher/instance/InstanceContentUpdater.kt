package hivens.launcher.instance

import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.util.sha1Of
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds newer builds of what an instance already has, and installs them.
 *
 * Scoped to files the caller says are the player's: an instance that tracks a
 * pack has its mods decided by that pack, and replacing one behind the pack's
 * back both breaks the record of what was installed and gets undone by the next
 * pack update. Which files those are is a question about the instance's origin,
 * so it is answered above this, and this takes the list.
 *
 * Runs live on the app scope rather than on a screen: updating forty mods is a
 * minute of downloads, and leaving the tab must not abandon a folder half
 * replaced. [runs] is how a screen that comes back finds out what happened
 * while it was away.
 *
 * Failure is per file. Modrinth's own launcher aborts the whole batch on the
 * first bad download; here the rest still land and the ones that did not are
 * named, because "nothing updated" is a much worse answer than "thirty eight of
 * forty updated, these two did not".
 */
class InstanceContentUpdater(
    private val modrinth: ModrinthClient,
    private val manager: InstanceContentManager,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(InstanceContentUpdater::class.java)

    /** An update to carry out: what to install, and the state to install it in. */
    data class Target(val update: ModUpdate, val enabled: Boolean)

    /**
     * How a batch is going. [current] is the file being worked on, for a line
     * that moves; [failed] names what did not land, and survives [finished] so
     * the screen can say so after the fact.
     */
    data class Run(
        val total: Int,
        val done: Int,
        val current: String?,
        val failed: List<String>,
        val finished: Boolean,
    ) {
        val succeeded: Int get() = done - failed.size
    }

    private val _runs = MutableStateFlow<Map<String, Run>>(emptyMap())
    val runs: StateFlow<Map<String, Run>> = _runs

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Runs are keyed by instance folder: one batch per instance, and it outlives any screen. */
    fun keyOf(instanceDir: Path): String = instanceDir.normalize().toString()

    /**
     * Ask Modrinth what is newer, for everything in [items] it recognises.
     *
     * Hashing is local and the questions are batched per folder kind, so a
     * hundred mods cost one walk of the disk and a handful of requests. A file
     * Modrinth does not index simply gets no answer, which is the correct one
     * for a private or hand-built jar.
     *
     * Blank [mcVersion] means the instance has no manifest to check against yet,
     * and every answer would be for the wrong game version -- so nothing is asked.
     */
    suspend fun check(
        instanceDir: Path,
        items: List<InstalledContent>,
        mcVersion: String,
        loader: String,
        channel: ModUpdateChannel = ModUpdateChannel.Release,
    ): Map<ContentRef, ModUpdate> = withContext(Dispatchers.IO) {
        if (mcVersion.isBlank() || items.isEmpty()) return@withContext emptyMap()

        // One hash per file, and a file that cannot be read is skipped rather
        // than failing the check for everything beside it.
        val hashed = items.mapNotNull { c ->
            runCatching { c to sha1Of(c.pathIn(instanceDir)) }
                .onFailure { log.debug("cannot hash {}: {}", c.fileName, it.message) }
                .getOrNull()
        }
        // Two identical files under different names are two rows, and both of
        // them are owed the same answer.
        val byHash = hashed.groupBy({ it.second }, { it.first })

        val found = mutableMapOf<ContentRef, ModUpdate>()
        for ((kind, group) in hashed.groupBy { it.first.kind }) {
            val loaders = loadersFor(kind, loader)
            if (loaders.isEmpty()) continue
            var remaining = group.map { it.second }.distinct()
            for (types in channel.rungs) {
                if (remaining.isEmpty()) break
                val answers = runCatching {
                    modrinth.latestForHashes(remaining, loaders, listOf(mcVersion), types)
                }.getOrElse {
                    log.warn("update check for {} failed on channel {}: {}", kind, types, it.message)
                    break
                }
                for ((hash, versions) in answers) {
                    for (installed in byHash[hash].orEmpty()) {
                        val ref = ContentRef(installed.kind, installed.fileName)
                        updateFrom(ref, hash, installed.version, versions)?.let { found[ref] = it }
                    }
                }
                // A file that got an answer on this rung has been answered,
                // update or not: a mod with a current release build must not be
                // offered an alpha on the next rung down.
                remaining = remaining - answers.keys
            }
        }
        found
    }

    /**
     * Start installing [targets]. Returns false when a batch for this instance
     * is already in flight -- pressing the button twice is one run, not two
     * racing over the same folder.
     *
     * [onChanged] fires after each file lands, so the list on screen fills in as
     * the batch goes rather than all at once at the end.
     */
    fun start(
        instanceDir: Path,
        targets: List<Target>,
        onChanged: suspend () -> Unit = {},
    ): Boolean {
        if (targets.isEmpty()) return false
        val key = keyOf(instanceDir)
        jobs[key]?.let { if (it.isActive) return false }

        _runs.update { it + (key to Run(total = targets.size, done = 0, current = null, failed = emptyList(), finished = false)) }
        val job = scope.launch {
            val gate = Semaphore(DOWNLOAD_CONCURRENCY)
            coroutineScope {
                targets.map { target ->
                    async {
                        val name = target.update.ref.fileName
                        mark(key) { it.copy(current = name) }
                        val ok = gate.withPermit { runCatching { applyOne(instanceDir, target) }.getOrDefault(false) }
                        mark(key) { run ->
                            run.copy(
                                done   = run.done + 1,
                                failed = if (ok) run.failed else run.failed + name,
                            )
                        }
                        if (ok) runCatching { onChanged() }
                    }
                }.awaitAll()
            }
            mark(key) { it.copy(current = null, finished = true) }
            runCatching { onChanged() }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        return true
    }

    /** Drop a finished run once the screen has shown its outcome. */
    fun dismiss(instanceDir: Path) {
        val key = keyOf(instanceDir)
        _runs.update { it - key }
    }

    /**
     * Download one update beside its folder, check it against the hash Modrinth
     * published for it, and swap it in.
     *
     * The scratch file lives in the target folder so the swap is a rename within
     * one filesystem, and under a name the scanner does not read as content. The
     * hash check is the difference between installing a mod and installing a
     * truncated download of one: unlike the browse-and-install path, an update
     * knows what it is supposed to receive.
     */
    private suspend fun applyOne(instanceDir: Path, target: Target): Boolean {
        val update = target.update
        val dir = instanceDir.resolve(update.ref.kind.folderName())
        withContext(Dispatchers.IO) { Files.createDirectories(dir) }
        val scratch = withContext(Dispatchers.IO) {
            Files.createTempFile(dir, ".nexira-update-", ".part").also {
                // The transfer skips a target that already exists, and
                // createTempFile has just made one.
                Files.deleteIfExists(it)
            }
        }
        return try {
            modrinth.downloadTo(update.url, scratch)
            val got = withContext(Dispatchers.IO) { sha1Of(scratch) }
            if (!got.equals(update.sha1, ignoreCase = true)) {
                log.warn("update for {} hashed {}, expected {}", update.ref.fileName, got, update.sha1)
                withContext(Dispatchers.IO) { Files.deleteIfExists(scratch) }
                return false
            }
            manager.replace(
                instanceDir = instanceDir,
                kind        = update.ref.kind,
                oldFileName = update.ref.fileName,
                source      = scratch,
                newFileName = update.fileName,
                enabled     = target.enabled,
            )
        } catch (e: Exception) {
            log.warn("updating {} to {} failed: {}", update.ref.fileName, update.versionNumber, e.message)
            withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(scratch) } }
            false
        }
    }

    private fun mark(key: String, edit: (Run) -> Run) {
        _runs.update { runs -> runs[key]?.let { runs + (key to edit(it)) } ?: runs }
    }

    private companion object {
        /**
         * Four at a time. The transfer engine retries and resumes, so the cap is
         * about not saturating a home connection while the launcher is also the
         * thing the player is looking at -- not about the server.
         */
        const val DOWNLOAD_CONCURRENCY = 4
    }
}
