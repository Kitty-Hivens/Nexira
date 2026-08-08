package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.api.interfaces.IPackSyncService
import hivens.core.api.interfaces.RosterInspection
import hivens.core.api.interfaces.RosterVerdict
import hivens.core.io.InstanceMutationLock
import hivens.core.io.fileOpRetry
import hivens.core.io.resolveWithinRoot
import hivens.core.net.Digest
import hivens.core.net.RepairReport
import hivens.core.net.DigestAlgorithm
import hivens.core.net.of
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.update.UpdatePlan
import hivens.launcher.util.ModArchives
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.StandardCopyOption
import java.util.Comparator

/**
 * v2-manifest sync. Parallel to [hivens.launcher.FileDownloadService] but speaks the
 * smrt mirror's flat `mods[] + assets[]` shape with a per-entry source
 * pointer, instead of SC's recursive `{directories,files}` tree.
 *
 * Throws on any download error or sha1 mismatch. The caller does not
 * get a partial-success indicator and there is no silent fallback to
 * the SC sync path -- mirror failures must surface, otherwise a broken
 * mirror is masked by a stale-but-working SC sync and the regression
 * stays invisible.
 */
class SmrtSyncService(
    private val client: SmrtPackClient,
    private val modrinth: ModrinthClient,
    private val transfers: TransferEngine,
) : IPackSyncService {
    private val log = LoggerFactory.getLogger(SmrtSyncService::class.java)

    /**
     * [enabledState] maps a mod `filename` to whether it should be active.
     * Required mods are always active regardless; an optional absent from the
     * map falls back to its manifest `default_enabled`. Empty map = install
     * every mod at its manifest default (the pre-toggle behaviour).
     */
    suspend fun sync(
        packId: String,
        clientDir: Path,
        progress: ((current: Int, total: Int, filename: String) -> Unit)? = null,
        enabledState: Map<String, Boolean> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        // Serialize against a concurrent structural mutation of this instance (an
        // optional-content toggle relabel), so a rename can't land between the
        // existence check and the move below. Reads are not gated -- they open
        // delete-shared and cannot corrupt a rename.
        InstanceMutationLock.withLock(clientDir) {
            val manifest = client.fetchManifest(packId)
            log.info(
                "smrt sync: pack={}, pack_version={}, mods={}, assets={}",
                manifest.packId, manifest.packVersion,
                manifest.mods.size, manifest.assets.size,
            )

            if (manifest.schemaVersion != EXPECTED_SCHEMA) {
                throw IOException(
                    "smrt mirror manifest schema_version=${manifest.schemaVersion}, " +
                        "expected $EXPECTED_SCHEMA. Update Nexira or the mirror version mismatched."
                )
            }

            Files.createDirectories(clientDir)

            // Which source laid this instance out last. SC used mods/<mcversion>/,
            // the mirror uses a flat mods/ -- worth a line in the log when it changes,
            // since a duplicate coremod from the old layout is a stack overflow rather
            // than a visible error. The sweep below needs no special case for it: it
            // removes archives the manifest does not name wherever they sit.
            val marker = clientDir.resolve(SOURCE_MARKER_FILE)
            val previousSource = readSourceMarker(marker)
            if (previousSource != SOURCE_MIRROR) {
                log.info("smrt sync: source is now {} (was {})", SOURCE_MIRROR, previousSource ?: "<none>")
            }

            // Planned first, fetched together. A pack is a hundred files of wildly
            // different sizes, and one request at a time meant a 300 MB resource pack
            // stalled every mod behind it -- the plan lets the engine overlap them and
            // split the large ones into blocks.
            val plan = ArrayList<Transfer>(manifest.mods.size + manifest.assets.size)
            for (mod in manifest.mods) {
                val enabled = enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)
                planMod(mod, clientDir, enabled)?.let { plan += it }
            }
            for (asset in manifest.assets) {
                planAsset(asset, clientDir)?.let { plan += it }
            }
            transfers.fetchAll(plan) { p -> progress?.invoke(p.filesDone, p.filesTotal, p.current) }

            // Drop manifest-removed mods and catch foreign payloads that
            // the wipe missed (an SC sync ran between two mirror syncs
            // without touching the marker, so the wipe gate saw a stale
            // "mirror" value). Only top-level mods/{expected_filename}
            // entries survive.
            val expected = manifest.mods.flatMap { listOf(it.filename, "${it.filename}.disabled") }.toSet()
            // One rule for both cases. The old split -- wipe everything on a source
            // change, drop stray jars otherwise -- dates from the clients era, where
            // SC laid mods out under mods/<mcversion>/ and a duplicate coremod loaded
            // twice. Since the sweep only ever removes archives the manifest does not
            // name, the migration case needs nothing extra.
            pruneForeignEntries(clientDir, expected)

            writeSourceMarker(marker, SOURCE_MIRROR)
            writeRoster(clientDir, expected)
        }
    }

    /**
     * Checks an installed pack against [manifest] and puts right whatever does not
     * match, fetching as little as the evidence allows.
     *
     * Two stages, and the split is about what a check may cost. Everything is first
     * measured locally against the manifest's size and sha1 -- the same test a
     * re-sync uses -- because a `modrinth` entry has to be resolved through an API
     * call before it even has a URL, and doing that for a hundred intact mods would
     * put a hundred requests behind a button that should mostly find nothing wrong.
     * Only what fails that test is handed to the engine, which compares it block by
     * block against the map taken when the file was installed and pulls just the
     * blocks that differ.
     *
     * The consequence is that a damaged file is read twice: once to notice, once to
     * locate. For a repair the user asked for, that is the right trade -- the
     * alternative is an API round trip per entry every time.
     *
     * Protected paths are left alone. A config the user edited is not damage.
     */
    suspend fun verifyAndRepair(
        clientDir: Path,
        manifest: SmrtPackManifest,
        enabledState: Map<String, Boolean> = emptyMap(),
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ): RepairReport = withContext(Dispatchers.IO) {
        InstanceMutationLock.withLock(clientDir) {
            val total = manifest.mods.size + manifest.assets.size
            val suspect = ArrayList<Transfer>()
            for (mod in manifest.mods) {
                val enabled = enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)
                planMod(mod, clientDir, enabled)?.let { suspect += it }
            }
            for (asset in manifest.assets) {
                planAsset(asset, clientDir)?.let { suspect += it }
            }
            log.info("repair: pack={}, {} of {} entries need a closer look", manifest.packId, suspect.size, total)
            val report = transfers.verifyAndRepair(suspect) { p ->
                progress?.invoke(p.filesDone, p.filesTotal, p.current)
            }
            // A verify is a full comparison against the manifest, so it is exactly the
            // moment the instance can be vouched for -- write the roster here too.
            // Without this, "verify and repair" checked every file and still left the
            // instance unverified at launch, which is not a distinction anyone can be
            // expected to guess.
            //
            // Only when the repair actually finished, though: a run that could not
            // fetch half the pack has not established anything, and vouching for it
            // would hand the next launch a token over an instance still missing files.
            if (report.failed.isEmpty()) {
                writeRoster(clientDir, manifest.mods.flatMap { listOf(it.filename, "${it.filename}.disabled") }.toSet())
            } else {
                log.warn("repair: {} entr(ies) still unresolved -- instance stays unverified: {}", report.failed.size, report.failed.keys)
            }

            // Everything the local check cleared counts as intact: it was measured
            // against the same manifest, just without a round trip.
            report.copy(checked = total, intact = total - suspect.size + report.intact)
        }
    }

    /**
     * Applies a precomputed [UpdateReconciler] plan against an already-fetched
     * [manifest]. The CALLER must hold [InstanceMutationLock] for [clientDir]:
     * this method deliberately does not take it, because [sync] does and the lock
     * is not reentrant; the update driver holds it across scan + reconcile + apply.
     *
     * - toAdd / toUpdate: download the target file. For a mod, [enabledState]
     *   decides active (`mods/<name>`) vs disabled (`mods/<name>.disabled`) and the
     *   stale variant is dropped first, so a user's optional-off choice is kept.
     * - conflicts: the pack's version is written beside the user's edit as
     *   `<path>.new`; the user's file is never overwritten.
     * - toDelete: removed (both variants for a mod path).
     * - skippedProtected: never touched.
     *
     * sha1 is verified after every download (a mismatch throws and drops the bad
     * bytes), same as [sync].
     */
    suspend fun applyUpdate(
        clientDir: Path,
        manifest: SmrtPackManifest,
        plan: UpdatePlan,
        enabledState: Map<String, Boolean> = emptyMap(),
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val index = buildEntryIndex(manifest)
        val total = plan.toAdd.size + plan.toUpdate.size + plan.conflicts.size + plan.toDelete.size
        var current = 0

        // Same shape as a full sync: the local moves and drops happen while the plan
        // is built, then everything that needs the network goes in one batch.
        val fetches = ArrayList<Transfer>(plan.toAdd.size + plan.toUpdate.size + plan.conflicts.size)
        for (path in plan.toAdd + plan.toUpdate) {
            val entry = index[path] ?: continue
            if (path.startsWith(MODS_PREFIX)) {
                val filename = path.removePrefix(MODS_PREFIX)
                val enabled = enabledState[filename] ?: true
                val active = resolveSafe(clientDir, path, "mod $filename")
                val disabled = resolveSafe(clientDir, "$path.disabled", "mod $filename")
                val dest = if (enabled) active else disabled
                val stale = if (enabled) disabled else active
                runCatching { fileOpRetry("update drop stale $filename") { Files.deleteIfExists(stale) } }
                plan(dest, entry.sha1, entry.size, entry.source, "mod $filename")?.let { fetches += it }
            } else {
                val dest = resolveSafe(clientDir, path, "asset $path")
                plan(dest, entry.sha1, entry.size, entry.source, "asset $path")?.let { fetches += it }
            }
        }

        for (path in plan.conflicts) {
            val entry = index[path] ?: continue
            val dest = resolveSafe(clientDir, "$path.new", "conflict $path")
            plan(dest, entry.sha1, entry.size, entry.source, "conflict $path")?.let { fetches += it }
        }

        transfers.fetchAll(fetches) { p ->
            progress?.invoke(p.filesDone, total, p.current)
        }
        current = fetches.size

        for (path in plan.toDelete) {
            current++
            progress?.invoke(current, total, path)
            val target = resolveSafe(clientDir, path, "prune $path")
            runCatching { fileOpRetry("update prune $path") { Files.deleteIfExists(target) } }
            if (path.startsWith(MODS_PREFIX)) {
                val disabled = resolveSafe(clientDir, "$path.disabled", "prune $path")
                runCatching { fileOpRetry("update prune $path disabled") { Files.deleteIfExists(disabled) } }
            }
        }

        // Place every mod at active / .disabled per enabledState even when its bytes did
        // not change: an optional flipped to required (or back) has no toAdd/toUpdate entry
        // but must still move, or a now-required mod would launch missing.
        relabel(clientDir, manifest.mods, enabledState)

        writeRoster(clientDir, manifest.mods.flatMap { listOf(it.filename, "${it.filename}.disabled") }.toSet())
    }

    /**
     * See [IPackSyncService.enforceRoster].
     *
     * The roster comes off disk rather than from the mirror, so this holds with no
     * network -- including an offline launch, which is exactly when a hand-placed jar
     * would otherwise go unchallenged. [sync] and [applyUpdate] write it.
     *
     * Only loadable archives are touched. A jar or zip outside the roster is the
     * whole problem -- it is what a loader would execute -- while everything else
     * under `mods/` is data: mod caches, Connector's remapped-jar store, our own
     * block maps. Deleting those protects nothing and costs a rebuild at best.
     */
    override suspend fun enforceRoster(clientDir: Path, expected: Map<String, String>?): RosterVerdict = withContext(Dispatchers.IO) {
        // The baseline outranks the roster file wherever it exists. Both answer
        // "which names belong here", but only one of them also answers "and with
        // which bytes", and only one of them lives somewhere its subject cannot
        // simply edit.
        val roster = expected?.keys ?: readRoster(clientDir)
        if (roster.isEmpty()) {
            log.warn("mods enforce: no roster for {}, mods/ left alone and the launch stays unverified", clientDir.fileName)
            return@withContext RosterVerdict(verified = false)
        }
        val sweep = InstanceMutationLock.withLock(clientDir) {
            pruneForeignEntries(clientDir, roster)
        }
        if (sweep.blocked.isNotEmpty()) {
            log.warn(
                "mods enforce: {} entr(ies) could not be removed from {}: {}",
                sweep.blocked.size, clientDir.fileName, sweep.blocked,
            )
        }
        // The sweep answers by name. With a baseline there is a second question --
        // whether what kept its name kept its bytes -- and that is where a jar
        // overwritten in place gets caught, which no name comparison can see.
        val digests = if (expected == null) DigestScan() else digestScan(clientDir, expected)
        if (digests.mismatched.isNotEmpty()) {
            log.warn(
                "mods enforce: {} file(s) in {} do not match the pack's baseline: {}",
                digests.mismatched.size, clientDir.fileName, digests.mismatched,
            )
        }
        if (digests.unreadable.isNotEmpty()) {
            log.warn(
                "mods enforce: {} file(s) in {} could not be read to check them: {}",
                digests.unreadable.size, clientDir.fileName, digests.unreadable,
            )
        }
        RosterVerdict(
            // Anything left behind means the instance was not brought in line, and a
            // file that resists deletion is the likeliest thing to have been left on
            // purpose.
            verified = sweep.blocked.isEmpty() && digests.mismatched.isEmpty() && digests.unreadable.isEmpty(),
            removed = sweep.removed,
            blocked = sweep.blocked,
            mismatched = digests.mismatched,
            unreadable = digests.unreadable,
        )
    }

    /**
     * See [IPackSyncService.inspectRoster]. Same roster resolution and the same rule
     * for what counts as foreign as [enforceRoster] -- shared through
     * [foreignEntries] and [digestScan] rather than restated, so the two can only
     * ever agree.
     */
    override suspend fun inspectRoster(clientDir: Path, expected: Map<String, String>?): RosterInspection =
        withContext(Dispatchers.IO) {
            val roster = expected?.keys ?: readRoster(clientDir)
            if (roster.isEmpty()) {
                log.warn("mods inspect: no roster for {}, nothing to hold it to", clientDir.fileName)
                return@withContext RosterInspection(checkable = false)
            }
            val foreign = foreignEntries(clientDir, roster).map { (_, relText) -> relText }.sorted()
            val digests = if (expected == null) DigestScan() else digestScan(clientDir, expected)
            RosterInspection(
                foreign = foreign,
                mismatched = digests.mismatched,
                unreadable = digests.unreadable,
            )
        }

    /** What comparing the pack's declared digests against disk could establish. */
    private data class DigestScan(
        val mismatched: List<String> = emptyList(),
        val unreadable: List<String> = emptyList(),
    )

    /**
     * Compares the bytes on disk against the digests the pack declared. A missing
     * file is neither answer -- that is an incomplete install, which the sync and
     * repair paths own; this asks only about what is there.
     *
     * "Could not read it" is kept apart from "it does not match". They are not the
     * same claim and the difference is not cosmetic: a read failure is what an
     * antivirus scanning a jar, or a handle the previous session has not dropped
     * yet, looks like from here, and folding it into the mismatch list accuses the
     * player of swapping a mod on the strength of a locked file. The read is
     * retried on the transient shapes first ([fileOpRetry]), so only a lock that
     * outlives the backoff is reported at all.
     */
    private fun digestScan(clientDir: Path, expected: Map<String, String>): DigestScan {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return DigestScan()
        val mismatched = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        for ((name, sha1) in expected) {
            if (sha1.isBlank()) continue
            val file = modsDir.resolve(name)
            if (!Files.isRegularFile(file)) continue
            val actual = runCatching { fileOpRetry("roster digest $name") { sha1Of(file) } }
                .onFailure { log.warn("mods enforce: cannot read {}: {}", name, it.toString()) }
                .getOrNull()
            when {
                actual == null -> unreadable += name
                !actual.equals(sha1, ignoreCase = true) -> mismatched += name
            }
        }
        return DigestScan(mismatched.sorted(), unreadable.sorted())
    }

    private fun sha1Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What one sweep of `mods/` managed to remove, and what refused to go. */
    private data class Sweep(val removed: List<String>, val blocked: List<String>)

    private data class ResolvableEntry(val source: SmrtSource, val sha1: String, val size: Long)

    private fun buildEntryIndex(manifest: SmrtPackManifest): Map<String, ResolvableEntry> {
        val index = LinkedHashMap<String, ResolvableEntry>()
        for (mod in manifest.mods) index["$MODS_PREFIX${mod.filename}"] = ResolvableEntry(mod.source, mod.sha1, mod.sizeBytes)
        for (asset in manifest.assets) index[asset.dest] = ResolvableEntry(asset.source, asset.sha1, asset.sizeBytes)
        return index
    }

    /**
     * Re-labels already-downloaded optional mods to match [enabledState] with NO
     * network: an active jar that should be off becomes `.disabled` and vice
     * versa. The toggle UI calls this -- the bytes are already on disk, only the
     * name (and thus whether Forge loads it) changes. A variant that is missing
     * on disk is left for the next full sync to fetch.
     */
    override fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>): List<String> {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return emptyList()
        val failed = mutableListOf<String>()
        for (mod in mods) {
            val enabled = enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)
            val active = resolveSafe(modsDir, mod.filename, "mod ${mod.filename}")
            val disabled = resolveSafe(modsDir, "${mod.filename}.disabled", "mod ${mod.filename}")
            val from = if (enabled) disabled else active
            val to = if (enabled) active else disabled
            if (Files.exists(from) && !Files.exists(to)) {
                runCatching {
                    fileOpRetry("smrt relabel ${mod.filename}") {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
                    }
                }.onFailure {
                    // A lock that outlives the retry means a holder we can't evict --
                    // typically the running game's classloader, which on Windows keeps
                    // the jar open without delete-sharing. The intent is already
                    // persisted in optionalContent, so the next launch's sync applies
                    // it; record the file instead of pretending the flip took effect.
                    failed += mod.filename
                    log.warn("smrt relabel: {} still held after retries; applies on next launch", mod.filename)
                }
            }
        }
        return failed
    }


    /**
     * Every loadable archive under `mods/` that [expected] does not name, deepest
     * first, paired with the '/'-joined relative path a person reads in a report.
     *
     * Pure: it walks and decides, and touches nothing. [pruneForeignEntries] is this
     * plus a delete, [inspectRoster] is this without one -- the rule for what counts
     * as foreign has to be the same in both, or a launch would be held to one
     * standard and the session that follows it to another.
     */
    private fun foreignEntries(clientDir: Path, expected: Set<String>): List<Pair<Path, String>> {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return emptyList()
        val found = mutableListOf<Pair<Path, String>>()
        Files.walk(modsDir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                if (p == modsDir) return@forEach
                // Dot-directories are tooling state, not content: Connector keeps its
                // remapped jars in `.connector`, we keep block maps in
                // `.nexira-blocks`. Emptying them makes the next launch rebuild
                // everything and defends against nothing -- no loader reads them as
                // mods. Directories are never removed, for the same reason.
                val rel = modsDir.relativize(p)
                // Inside a dot-DIRECTORY, not merely dot-named: `.connector/x.jar` is
                // tooling state, while `.cheat.jar` sitting in mods/ is a mod the
                // loader will happily read (its discovery matches `.+\.jar`, leading
                // dot and all). Testing the first segment alone let that one through.
                if (rel.nameCount > 1 && rel.getName(0).toString().startsWith(".")) return@forEach
                if (!Files.isRegularFile(p)) return@forEach
                // Only what a loader would execute. A config, or a leftover .tmp
                // beside the mods, is not a way to run code.
                if (!ModArchives.isLoadable(p.fileName.toString())) return@forEach
                val keep = p.parent == modsDir && p.fileName.toString() in expected
                if (keep) return@forEach
                // Joined over the path's own segments rather than toString(): the
                // report is read by a person and matched against manifest paths, both
                // of which use '/' whatever the host separator is.
                found += p to rel.joinToString("/")
            }
        }
        return found
    }

    /**
     * Removes everything under `mods/` that the manifest does not name, keeping the
     * files just downloaded. Replaces the old wipe-then-download order: the same end
     * state, reached without a window in which the instance holds neither the old
     * content nor the new one.
     *
     * Walked deepest-first so a directory is considered after its children; one that
     * still holds a kept file refuses to delete and is left alone.
     */
    private fun pruneForeignEntries(clientDir: Path, expected: Set<String>): Sweep {
        val removed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        for ((path, relText) in foreignEntries(clientDir, expected)) {
            runCatching { fileOpRetry("smrt drop foreign $path") { Files.delete(path) } }
                .onSuccess { removed += relText }
                // Only regular files reach this point, so a refusal is always an
                // obstruction: something is holding the file or denying the delete.
                .onFailure { blocked += relText }
        }
        if (removed.isNotEmpty()) log.info("smrt sync: dropped {} foreign entr(ies) from mods/: {}", removed.size, removed)
        return Sweep(removed, blocked)
    }

    /**
     * The set of `mods/` names the installed pack consists of, one per line. Written
     * on every sync and update so a launch can hold the instance to it without asking
     * the mirror; read back as a set, blank lines dropped.
     */
    private fun readRoster(clientDir: Path): Set<String> =
        clientDir.resolve(ROSTER_FILE).toFile()
            .takeIf { it.isFile }
            ?.runCatching { readLines().map(String::trim).filter { line -> line.isNotEmpty() }.toSet() }
            ?.onFailure { log.warn("mods enforce: unreadable roster, treating as absent", it) }
            ?.getOrNull()
            .orEmpty()

    private fun writeRoster(clientDir: Path, expected: Set<String>) {
        runCatching { clientDir.resolve(ROSTER_FILE).toFile().writeText(expected.sorted().joinToString("\n")) }
            .onFailure { log.warn("smrt sync: failed to write mods roster", it) }
    }

    private fun readSourceMarker(marker: Path): String? =
        marker.toFile()
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun writeSourceMarker(marker: Path, value: String) {
        runCatching { marker.toFile().writeText(value) }
            .onFailure { log.warn("smrt sync: failed to write source marker", it) }
    }

    /**
     * Mods land at `mods/{filename}`; an optional mod toggled OFF lands at
     * `mods/{filename}.disabled` (Forge ignores non-`.jar` names), so flipping a
     * toggle is a rename rather than a re-download. When the stale variant
     * already holds the right bytes it is moved into place; otherwise it is
     * removed and the active variant fetched. Forge 1.12.2 scans both `mods/`
     * and `mods/{mcversion}/`, so flat placement still loads.
     */
    private suspend fun planMod(mod: SmrtModEntry, clientDir: Path, enabled: Boolean): Transfer? {
        val modsDir = clientDir.resolve("mods")
        val activeDest = resolveSafe(modsDir, mod.filename, "mod ${mod.filename}")
        val disabledDest = resolveSafe(modsDir, "${mod.filename}.disabled", "mod ${mod.filename}")
        val dest = if (enabled) activeDest else disabledDest
        val stale = if (enabled) disabledDest else activeDest

        if (!isUpToDate(dest, mod.sha1, mod.sizeBytes) && isUpToDate(stale, mod.sha1, mod.sizeBytes)) {
            Files.createDirectories(dest.parent)
            fileOpRetry("smrt sync move ${mod.filename}") { Files.move(stale, dest, StandardCopyOption.REPLACE_EXISTING) }
            return null
        }
        runCatching { fileOpRetry("smrt sync drop stale ${mod.filename}") { Files.deleteIfExists(stale) } }
        return plan(dest, mod.sha1, mod.sizeBytes, mod.source, "mod ${mod.filename}")
    }

    /**
     * No name-based exemption here, deliberately.
     *
     * The `clients/` path needs one: it downloads whatever the server's manifest
     * lists, with no record of what it put there last time, so without a list of
     * names to leave alone it overwrites a player's own settings. The mirror was
     * built to replace that path and does not have the problem -- it keeps the
     * installed version's manifest as a baseline, so the reconciler can tell a file
     * the pack shipped from one the player wrote, and answers each case on its own.
     *
     * Carrying the list here made the mirror worse rather than safer: a name on it
     * was skipped in BOTH directions, so a pack could not deliver its own
     * `servers.dat` or its JEI settings at all -- the pack ships the server list on
     * purpose, and the exemption silently dropped it.
     */
    private suspend fun planAsset(asset: SmrtAssetEntry, clientDir: Path): Transfer? {
        val dest = resolveSafe(clientDir, asset.dest, "asset ${asset.dest}")
        return plan(dest, asset.sha1, asset.sizeBytes, asset.source, "asset ${asset.dest}")
    }

    /**
     * Resolves [relative] against [root] and rejects entries that
     * escape the root via `..` segments or absolute paths. A hostile
     * or buggy manifest could otherwise hand the launcher
     * `../../../etc/cron.d/payload` and end up overwriting arbitrary
     * files writable by the launcher process. The mirror is trusted
     * but the boundary check is cheap and means a single bad
     * manifest entry can never escape the per-instance directory.
     *
     * **Threat model**: defends against MANIFEST-DRIVEN traversal
     * (a bad/hostile mirror manifest entry). Does NOT defend against
     * a pre-existing symlink inside `root` that points outside --
     * the lexical [Path.normalize] check is purely string-based, so
     * `<root>/config -> /opt/shared-configs` followed by manifest
     * entry `config/foo.cfg` writes to /opt/shared-configs/foo.cfg
     * even though the lexical check passes. Symlinks under `<root>`
     * are assumed user-installed and trusted; if the threat model
     * ever broadens (multi-tenant installs, sandboxed sync), switch
     * to `toRealPath(NOFOLLOW_LINKS)` per parent component before
     * the startsWith comparison.
     */
    private fun resolveSafe(root: Path, relative: String, label: String): Path =
        resolveWithinRoot(root, relative, label)

    /**
     * The transfer for one manifest entry, or null when there is nothing to fetch.
     *
     * The up-to-date check happens here rather than being left to the engine
     * because of what sits between: a `modrinth` source needs an API round trip to
     * turn into a URL, and doing that for the ninety-odd mods already on disk would
     * put a hundred needless requests in front of every re-sync.
     */
    private suspend fun plan(
        dest: Path,
        expectedSha1: String,
        expectedSize: Long,
        source: SmrtSource,
        label: String,
    ): Transfer? {
        if (source is SmrtSource.Unknown) {
            // Forward-compat: a source type this launcher version does not
            // understand. Skip the entry instead of failing the whole sync.
            log.warn("smrt sync: skipping {} -- unsupported source type; update the launcher to install it", label)
            return null
        }
        if (isUpToDate(dest, expectedSha1, expectedSize)) {
            return null
        }
        val url = resolveUrl(source)
        log.debug("smrt sync: fetching {} <- {}", label, url)
        return Transfer(
            url = url,
            dest = dest,
            expect = Digest(DigestAlgorithm.SHA1, expectedSha1),
            size = expectedSize,
        )
    }

    /**
     * Resolves a [SmrtSource] to an actual download URL. The two
     * mirror-hosted variants carry the URL inline in the manifest;
     * `modrinth` needs a round-trip to Modrinth's API to fetch the
     * version's primary file URL. Picks the file flagged `primary:
     * true`, falling back to `files[0]` only if no entry is marked
     * primary -- Modrinth versions often ship multiple artifacts
     * (sources, deobf, signatures) and the first is not guaranteed
     * to be the installable one.
     */
    private suspend fun resolveUrl(source: SmrtSource): String = when (source) {
        is SmrtSource.SmrtCache  -> source.url
        is SmrtSource.SmrtStatic -> source.url
        is SmrtSource.Modrinth   -> {
            val v = modrinth.resolveVersion(source.projectId, source.versionId)
            v.primaryFile().url
        }
        // Unreachable: downloadIfNeeded skips Unknown before resolving a URL.
        // Kept exhaustive so a new SmrtSource variant forces a decision here.
        is SmrtSource.Unknown    -> error("resolveUrl called on an unsupported source")
    }

    /**
     * Up-to-date check: file exists, right size, right sha1. Cheap
     * shortcut to skip downloads on re-sync of the same manifest.
     * Size check first so a totally wrong file fails fast without a
     * full hash walk.
     */
    private fun isUpToDate(dest: Path, expectedSha1: String, expectedSize: Long): Boolean {
        if (!Files.exists(dest) || !Files.isRegularFile(dest)) return false
        if (Files.size(dest) != expectedSize) return false
        return DigestAlgorithm.SHA1.of(dest).equals(expectedSha1, ignoreCase = true)
    }


    companion object {
        /**
         * The wire-format generation this client understands. Mirror
         * may serve a higher schema_version after a wire-incompatible
         * change; this client must refuse rather than misinterpret.
         */
        const val EXPECTED_SCHEMA = 2

        private const val SOURCE_MARKER_FILE = ".nexira-sync-source"

        /** Names `mods/` may hold, written by sync/update and enforced on launch. */
        private const val ROSTER_FILE = ".nexira-mods"
        private const val SOURCE_MIRROR = "mirror"
        private const val MODS_PREFIX = "mods/"
    }
}
