package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.flatten
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.modrinth.Placement
import hivens.launcher.modrinth.PlacementRefusal
import hivens.launcher.modrinth.chooseBuild
import hivens.launcher.modrinth.placementIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Where an install puts things. A pack today. A world inside one is where data packs will go. */
sealed interface ContentDestination {

    /** An installed pack, with what it runs, which decides what fits. */
    data class Pack(
        val instanceId: String,
        val dir: Path,
        val origin: PackOrigin,
        val mcVersion: String,
        /** The pack's loader as the catalogue names loaders, blank for none. */
        val loader: String,
        /**
         * The files the pack itself placed, by path, where the pack's record says so:
         * a mirror pack's installed build. Null where the instance keeps a record of
         * its own on disk instead, or none at all.
         */
        val packFiles: Set<String>? = null,
    ) : ContentDestination {
        companion object {
            /** The pack [instance] as a destination, its files under [instancesDir]. */
            fun of(instance: PackInstance, instancesDir: Path) = Pack(
                instanceId = instance.id,
                dir = instancesDir.resolve(instance.instanceDirName),
                origin = instance.packRef.origin,
                mcVersion = instance.cachedManifest?.minecraftVersion.orEmpty(),
                loader = instance.cachedManifest?.loaderName
                    ?.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
                    ?.lowercase()
                    .orEmpty(),
                packFiles = if (instance.packRef.origin == PackOrigin.Mirror) instance.installedManifest?.flatten()?.keys else null,
            )
        }
    }
}

/**
 * Puts a catalogue build into a pack, together with what it cannot run without.
 *
 * An install is planned in full before a byte is written and then applied as one
 * piece of work on the instance. The plan says what goes where and names every
 * project it leaves out and why, so a screen can show the reasons instead of a
 * bare count. Applying it is the part that touches the folders, and it runs with
 * the instance marked busy, so no game starts over a half-installed mod, and never
 * while its game is running.
 *
 * Each file is downloaded beside its folder and checked against the hash the
 * catalogue published before it is put in place. Another build of a project the
 * pack already has replaces it rather than landing beside it, and keeps it on or
 * off as it was. A file with the very bytes the build publishes is already
 * installed, whatever it is called.
 *
 * What a pack takes is decided by where it came from. A pack of the player's own
 * takes anything. A mirror pack takes resource and shader packs only: before a
 * bound launch the mirror deletes every mod its roster does not name. Another
 * tracked pack takes the player's files only when it keeps a record of its own,
 * since an update retires what the record names and nothing else.
 */
class ContentInstaller(
    private val modrinth: ModrinthClient,
    private val index: InstalledIndex,
    private val manager: InstanceContentManager,
    private val work: InstanceWorkRegistry,
    private val running: RunningPackSource,
) {
    private val log = LoggerFactory.getLogger(ContentInstaller::class.java)

    /** One installs per instance at a time. A second click waits its turn instead of racing the first for a name. */
    private val turns = ConcurrentHashMap<String, Mutex>()

    /** A file to fetch and where it goes. [replaces] is the file of the same project it takes the place of. */
    data class Step(
        val version: ModrinthVersion,
        val kind: ContentKind,
        val replaces: InstalledIndex.Entry?,
        /** The build that was asked for, rather than one pulled in behind it. */
        val head: Boolean,
    ) {
        val fileName: String get() = version.primaryFile().filename
        val projectId: String get() = version.projectId
    }

    /** A project the install leaves out, and why. */
    sealed interface Skip {
        val projectId: String?

        /** A dependency the pack already carries. Kept as it is, whatever build. */
        data class Present(override val projectId: String) : Skip

        /** The very build asked for is already in the pack. */
        data class AlreadyInstalled(override val projectId: String, val ref: ContentRef) : Skip

        /** A required dependency with no build that runs on this pack. */
        data class NoBuild(override val projectId: String?) : Skip

        /** A dependency the catalogue could not be asked about. */
        data class LookupFailed(override val projectId: String?) : Skip

        /** A build that has no place in a pack, see [PlacementRefusal]. */
        data class NotPlaceable(override val projectId: String, val reason: PlacementRefusal) : Skip

        /** A build of a kind this pack does not take from the player. */
        data class NotAllowed(override val projectId: String, val kind: ContentKind) : Skip

        /** Another file already holds the name, or the name is not a plain file name. */
        data class NameTaken(override val projectId: String, val fileName: String) : Skip

        /** The download or the move did not go through. */
        data class Failed(override val projectId: String, val fileName: String) : Skip

        /** Not tried, because the build that was asked for did not land. */
        data class NotAttempted(override val projectId: String) : Skip

        /**
         * A required dependency deeper than the walk goes. Named rather than left
         * out, so a chain longer than [MAX_DEPTH] reads as missing something and
         * not as complete.
         */
        data class TooDeep(override val projectId: String) : Skip

        /**
         * The file the build would replace is one the pack put there. An update of
         * the pack puts its own file back and leaves the replacement beside it, so
         * the pack would hold two builds of one mod.
         */
        data class PackOwned(override val projectId: String, val ref: ContentRef) : Skip
    }

    /** What an install will do: the files in order, head first, and what it leaves out. */
    data class Plan(
        val destination: ContentDestination.Pack,
        val steps: List<Step>,
        val skipped: List<Skip>,
        /** Projects the pack carried when the plan was made. */
        val present: Set<String>,
    )

    /** One file that landed. */
    data class Landed(val projectId: String, val ref: ContentRef)

    /** Why an install did not run at all. */
    sealed interface Refusal {
        data object GameRunning : Refusal
        data class Busy(val work: InstanceWork) : Refusal
    }

    sealed interface Result {
        /** It ran. [present] is every project the pack carries afterwards. */
        data class Done(
            val landed: List<Landed>,
            val skipped: List<Skip>,
            val present: Set<String>,
        ) : Result

        data class Refused(val reason: Refusal) : Result
    }

    /**
     * Install [head] into [destination] with its required dependencies, walked
     * [depth] levels down.
     *
     * Refused while the pack's game runs or other work rewrites it. Another install
     * is not a reason: it is waited for. The running game is asked about again once
     * this install is marked, which closes the window in which a launch could be
     * accepted between the two.
     */
    suspend fun install(destination: ContentDestination.Pack, head: ModrinthVersion, depth: Int = MAX_DEPTH): Result {
        val id = destination.instanceId
        if (running.runningPackInstanceId.value == id) return Result.Refused(Refusal.GameRunning)
        val mark = when (val c = work.claim(id, InstanceWork.ContentInstall, alongside = setOf(InstanceWork.ContentInstall))) {
            is InstanceWorkRegistry.Claim.Held -> c.mark
            is InstanceWorkRegistry.Claim.Taken -> return Result.Refused(Refusal.Busy(c.by))
        }
        try {
            if (running.runningPackInstanceId.value == id) return Result.Refused(Refusal.GameRunning)
            return turns.computeIfAbsent(id) { Mutex() }.withLock { apply(plan(destination, head, depth)) }
        } finally {
            mark.release()
        }
    }

    /**
     * What installing [head] into [destination] would do, read from the pack as it
     * is now. Touches no file.
     *
     * The walk is breadth first and bounded: a dependency graph is a graph, two
     * mods can want the same API, and a malformed one can point at itself. A pin
     * names the build of a dependency the author made theirs against, and it is
     * taken over the newest. Only required dependencies are followed: an optional
     * one is the author's suggestion, not an instruction.
     */
    suspend fun plan(destination: ContentDestination.Pack, head: ModrinthVersion, depth: Int = MAX_DEPTH): Plan {
        val snapshot = index.read(destination.dir)
        // A folder the catalogue could not identify cannot say which of its files is
        // another build of what is about to be installed, and guessing "none" is how
        // a pack ends up with two jars of one mod and does not start.
        if (!snapshot.complete) {
            return Plan(destination, emptyList(), listOf(Skip.LookupFailed(head.projectId)), snapshot.projects)
        }
        val recorded = withContext(Dispatchers.IO) { PackPlacedContent.paths(destination.dir) }
        val keepsRecord = recorded != null
        // A mirror pack names its files in its installed build rather than in a
        // record on disk, and a resource pack it ships comes back on its next sync
        // or repair however it was replaced.
        val packFiles = destination.packFiles ?: recorded
        val present = snapshot.projects.toMutableSet()
        val planned = mutableSetOf<String>()
        val steps = mutableListOf<Step>()
        val skipped = mutableListOf<Skip>()
        val seen = mutableSetOf<String>()

        var frontier = listOf(head)
        var level = 0
        while (frontier.isNotEmpty() && level <= depth) {
            val next = mutableListOf<ModrinthVersion>()
            for (v in frontier) {
                if (!seen.add(v.id)) continue
                val isHead = level == 0
                if (!isHead && (v.projectId in present || v.projectId in planned)) {
                    skipped += Skip.Present(v.projectId)
                    continue
                }
                val placement = v.placementIn(destination.loader, destination.mcVersion)
                if (placement is Placement.Refused) {
                    skipped += Skip.NotPlaceable(v.projectId, placement.reason)
                    continue
                }
                val kind = (placement as Placement.Into).kind
                if (!takesFromPlayer(destination.origin, kind, keepsRecord)) {
                    skipped += Skip.NotAllowed(v.projectId, kind)
                    continue
                }
                if (v.files.isEmpty()) {
                    skipped += Skip.NoBuild(v.projectId)
                    continue
                }
                val file = v.primaryFile()
                val same = snapshot.withSha1(file.hashes.sha1)
                if (same != null) {
                    // Already there, under whatever name. Its dependencies are still
                    // walked: a mod put in by hand often came without them.
                    if (isHead) skipped += Skip.AlreadyInstalled(v.projectId, same.ref) else skipped += Skip.Present(v.projectId)
                    present += v.projectId
                } else {
                    val replaces = if (isHead) snapshot.of(v.projectId).firstOrNull() else null
                    if (replaces != null && packFiles != null && replaces.ref.relativePath() in packFiles) {
                        skipped += Skip.PackOwned(v.projectId, replaces.ref)
                        continue
                    }
                    val holder = snapshot.at(ContentRef(kind, file.filename))
                    if (!isBareFileName(file.filename) || (holder != null && holder != replaces)) {
                        skipped += Skip.NameTaken(v.projectId, file.filename)
                        continue
                    }
                    steps += Step(v, kind, replaces, isHead)
                    planned += v.projectId
                }
                for (dep in v.dependencies) {
                    val id = dep.projectId ?: continue
                    if (dep.dependencyType == "required" && (id in present || id in planned)) skipped += Skip.Present(id)
                }
                for (dep in requiredDependencies(v, present + planned)) {
                    // The last level follows nothing further, so what it needs is
                    // named here instead of being asked about and then dropped.
                    if (level == depth) {
                        dep.projectId?.let { skipped += Skip.TooDeep(it) }
                        continue
                    }
                    when (val r = resolveDependency(dep.versionId, dep.projectId, destination)) {
                        is Resolved.Build -> next += r.version
                        Resolved.None -> skipped += Skip.NoBuild(dep.projectId)
                        Resolved.Unreachable -> skipped += Skip.LookupFailed(dep.projectId)
                    }
                }
            }
            frontier = next
            level++
        }
        return Plan(destination, steps, skipped.distinct(), snapshot.projects)
    }

    private sealed interface Resolved {
        data class Build(val version: ModrinthVersion) : Resolved
        data object None : Resolved
        data object Unreachable : Resolved
    }

    private suspend fun resolveDependency(versionId: String?, projectId: String?, destination: ContentDestination.Pack): Resolved = try {
        val v = when {
            versionId != null && projectId != null -> modrinth.resolveVersion(projectId, versionId)
            projectId != null -> chooseBuild(modrinth.listVersions(projectId), destination.mcVersion, destination.loader)
            else -> null
        }
        if (v == null) Resolved.None else Resolved.Build(v)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("resolving dependency {}/{} failed: {}", projectId, versionId, e.message)
        Resolved.Unreachable
    }

    /**
     * Carry out [plan]: each file fetched to scratch, checked, and put in place,
     * the head first. When the head does not land, nothing behind it is tried: a
     * pack holding a mod's dependencies without the mod is not what was asked for.
     */
    private suspend fun apply(plan: Plan): Result.Done {
        val dir = plan.destination.dir
        sweepContentScratch(dir, plan.steps.map { it.kind }.distinct())
        val landed = mutableListOf<Landed>()
        val skipped = plan.skipped.toMutableList()
        for ((i, step) in plan.steps.withIndex()) {
            if (fetchAndPut(dir, step)) {
                landed += Landed(step.projectId, ContentRef(step.kind, step.fileName))
                continue
            }
            skipped += Skip.Failed(step.projectId, step.fileName)
            if (step.head) {
                plan.steps.drop(i + 1).forEach { skipped += Skip.NotAttempted(it.projectId) }
                break
            }
        }
        val present = plan.present + landed.map { it.projectId } +
            skipped.filterIsInstance<Skip.AlreadyInstalled>().map { it.projectId }
        // A dependency named from one branch of the walk can land from another, the
        // same level reaching it twice, and what landed is not left out.
        val landedIds = landed.mapTo(HashSet()) { it.projectId }
        return Result.Done(landed, skipped.filterNot { it.projectId in landedIds }, present)
    }

    private suspend fun fetchAndPut(dir: Path, step: Step): Boolean {
        val file = step.version.primaryFile()
        // Inside the try: a folder that cannot take a scratch file (a full disk, a
        // file where the folder should be) is this step failing, and thrown out of
        // here it took the steps that had already landed out of the answer.
        var scratch: Path? = null
        return try {
            scratch = newContentScratch(dir.resolve(step.kind.folderName()))
            modrinth.downloadTo(file.url, scratch, file.hashes.sha1)
            val old = step.replaces
            when {
                old != null && old.ref.kind == step.kind ->
                    manager.replace(dir, step.kind, old.ref.fileName, scratch, step.fileName, enabled = old.enabled)
                else -> {
                    // A different folder: a build the old installer put in mods/
                    // whatever it was. The new file goes where it belongs and the
                    // old one goes once it has.
                    val placed = manager.place(dir, step.kind, scratch, step.fileName, enabled = old?.enabled ?: true)
                    if (placed && old != null) manager.delete(dir, old.ref.kind, old.ref.fileName)
                    placed
                }
            }
        } catch (e: CancellationException) {
            scratch?.let { s -> withContext(NonCancellable + Dispatchers.IO) { runCatching { Files.deleteIfExists(s) } } }
            throw e
        } catch (e: Exception) {
            log.warn("installing {} failed: {}", step.fileName, e.message)
            scratch?.let { s -> withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(s) } } }
            false
        }
    }

    companion object {
        /**
         * How deep the walk goes. Three levels covers a mod wanting an API that
         * wants a core library. Past that a pack is describing something other
         * than a dependency chain.
         */
        const val MAX_DEPTH = 3
    }
}

/**
 * Whether a pack from [origin] takes a file of [kind] from the player.
 * [keepsRecord] is whether the instance has a record of what its pack placed. See
 * [ContentInstaller] for why each origin answers as it does.
 */
fun takesFromPlayer(origin: PackOrigin, kind: ContentKind, keepsRecord: Boolean): Boolean = when (origin) {
    PackOrigin.Local -> true
    PackOrigin.Mirror -> kind != ContentKind.Mod
    else -> keepsRecord
}

/** Where [this] sits inside an instance, as the pack's own record writes paths. */
internal fun ContentRef.relativePath(): String = "${kind.folderName()}/$fileName"
