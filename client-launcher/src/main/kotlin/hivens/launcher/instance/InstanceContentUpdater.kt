package hivens.launcher.instance

import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.util.sha1Of
import kotlinx.coroutines.CancellationException
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
    private val work: InstanceWorkRegistry,
) {

    private val log = LoggerFactory.getLogger(InstanceContentUpdater::class.java)

    /** An update to carry out: what to install, and the state to install it in. */
    data class Target(val update: ModUpdate, val enabled: Boolean)

    /**
     * What a check found, and whether it got to ask everything it meant to.
     *
     * The two are separate answers and the screen needs both: a request that
     * failed leaves [updates] holding whatever DID come back, which is worth
     * showing, while [complete] is what stops "we could not ask" being drawn as
     * "there is nothing new".
     */
    data class CheckOutcome(val updates: Map<ContentRef, ModUpdate>, val complete: Boolean)

    /**
     * How a batch is going. [current] is the file being worked on, for a line
     * that moves; [failed] names what did not land, and survives [finished] so
     * the screen can say so after the fact.
     */
    data class Run(
        /** The instance this batch belongs to, by name, for a surface that reports it. */
        val title: String,
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

    private val cached = ConcurrentHashMap<String, Checked>()

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
        force: Boolean = false,
    ): CheckOutcome = withContext(Dispatchers.IO) {
        if (mcVersion.isBlank() || items.isEmpty()) return@withContext CheckOutcome(emptyMap(), true)

        // Leaving the tab and coming back is one click, and without this it was
        // also a full round of requests. Short-lived on purpose: the answer goes
        // stale the moment an author publishes, and the refresh beside the button
        // is there for exactly that.
        val key = keyOf(instanceDir)
        if (!force) {
            cached[key]?.takeIf { it.fresh(items) }?.let { return@withContext CheckOutcome(it.updates, true) }
        }

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

        // What is installed, as Modrinth knows it: the publish date each answer
        // has to beat, and the channel each file is actually on. Without this a
        // machine running a beta is told, every single check, that a release
        // from a year earlier is an update.
        var complete = true
        val current = try {
            modrinth.versionsForHashes(hashed.map { it.second })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("resolving installed versions failed: {}", e.message)
            complete = false
            emptyMap()
        }

        val found = mutableMapOf<ContentRef, ModUpdate>()
        // A request that did not come back leaves this run incomplete, and an
        // incomplete run must not be remembered: cached, "we could not ask" would
        // be served as "there is nothing" for the next ten minutes.
        for ((group, hashes) in hashed.groupBy(
            { (item, hash) -> item.kind to effectiveChannel(channel, current[hash]?.versionType) },
            { it.second },
        )) {
            val (kind, askChannel) = group
            val loaders = loadersFor(kind, loader)
            if (loaders.isEmpty()) continue
            var remaining = hashes.distinct()
            for (types in askChannel.rungs) {
                if (remaining.isEmpty()) break
                val answers = try {
                    modrinth.latestForHashes(remaining, loaders, listOf(mcVersion), types)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("update check for {} failed on channel {}: {}", kind, types, e.message)
                    complete = false
                    break
                }
                for ((hash, versions) in answers) {
                    for (installed in byHash[hash].orEmpty()) {
                        val ref = ContentRef(installed.kind, installed.fileName)
                        updateFrom(
                            ref                  = ref,
                            installedSha1        = hash,
                            installedVersion     = installed.version,
                            candidates           = versions,
                            installedPublishedAt = current[hash]?.datePublished,
                        )?.let { found[ref] = it }
                    }
                }
                // A file that got an answer on this rung has been answered,
                // update or not: a mod with a current release build must not be
                // offered an alpha on the next rung down.
                remaining = remaining - answers.keys
            }
        }
        // Only a complete answer is worth remembering; a partial one is handed
        // back for what it has and asked again next time.
        if (complete) {
            cached[key] = Checked(found, items.map { ContentRef(it.kind, it.fileName) }.toSet(), System.nanoTime())
        }
        CheckOutcome(found, complete)
    }

    /**
     * A check, and what it was a check OF. The file set is part of the key: a mod
     * added or deleted since means the cached answer is about a different folder,
     * and a folder someone just dropped a jar into is precisely when the answer
     * matters.
     */
    private class Checked(val updates: Map<ContentRef, ModUpdate>, val of: Set<ContentRef>, val at: Long) {
        fun fresh(items: List<InstalledContent>): Boolean =
            System.nanoTime() - at < CHECK_TTL_NANOS &&
                of == items.mapTo(mutableSetOf()) { ContentRef(it.kind, it.fileName) }
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
        instanceId: String,
        instanceDir: Path,
        title: String,
        targets: List<Target>,
        onChanged: suspend () -> Unit = {},
    ): Boolean {
        if (targets.isEmpty()) return false
        val key = keyOf(instanceDir)
        jobs[key]?.let { if (it.isActive) return false }

        _runs.update { it + (key to Run(title = title, total = targets.size, done = 0, current = null, failed = emptyList(), finished = false)) }
        // Marked for as long as files are being swapped, so a game is not started
        // over a folder that is half the old mods and half the new ones.
        val job = scope.launch {
            work.during(instanceId, InstanceWork.ContentUpdate) { runBatch(key, instanceDir, targets, onChanged) }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        return true
    }

    private suspend fun runBatch(
        key: String,
        instanceDir: Path,
        targets: List<Target>,
        onChanged: suspend () -> Unit,
    ) {
        // A download interrupted by the app closing leaves its scratch file
        // behind. The scanner ignores those, so nobody would ever see them
        // and nothing else would ever remove them.
        sweepScratch(instanceDir, targets.map { it.update.ref.kind }.distinct())
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
            Files.createTempFile(dir, SCRATCH_PREFIX, SCRATCH_SUFFIX).also {
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

    /** Remove scratch files an interrupted batch left in the folders being touched. */
    private suspend fun sweepScratch(instanceDir: Path, kinds: List<ContentKind>) = withContext(Dispatchers.IO) {
        for (kind in kinds) {
            val dir = instanceDir.resolve(kind.folderName())
            runCatching {
                if (!Files.isDirectory(dir)) return@runCatching
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith(SCRATCH_PREFIX) }
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
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

        /**
         * Scratch naming. The prefix is what the sweep recognises; both halves
         * keep the file out of what the scanner reads as content, so a download
         * in flight never appears in the list as a broken mod.
         */
        const val SCRATCH_PREFIX = ".nexira-update-"
        const val SCRATCH_SUFFIX = ".part"

        /**
         * How long a check answers for. Ten minutes, the same figure Modrinth's
         * own client settled on: long enough that walking between tabs costs
         * nothing, short enough that it cannot be the reason a fix published
         * this morning is invisible this afternoon.
         */
        val CHECK_TTL_NANOS = 10L * 60 * 1_000_000_000
    }
}
