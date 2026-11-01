package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.modrinth.Placement
import hivens.launcher.modrinth.chooseBuild
import hivens.launcher.modrinth.placementIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Which packs a catalogue project could go into, and why the others could not.
 *
 * Asked by an install that has no pack behind it, a project found from the
 * launcher's own search rather than from inside a pack. Every pack gets an answer,
 * the ones it does not fit included: a list that quietly leaves a pack out reads as
 * a launcher that forgot the pack exists.
 *
 * The project's build list is asked for once and every pack is judged against it
 * with the same rule an install uses, [chooseBuild]. Whether a pack already has the
 * project is read against that same list ([InstalledIndex.readAgainst]), on this
 * machine: the catalogue is not told what any pack holds just because a dialog
 * opened.
 */
class InstallTargets(
    private val modrinth: ModrinthClient,
    private val index: InstalledIndex,
    private val repository: IPackRepository,
    /** The launcher's data directory, whose `instances/` holds every pack's folder. */
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(InstallTargets::class.java)

    /** Why a pack cannot take the project. */
    enum class Unfit {
        /** No build of the project runs on the pack's game version and loader. */
        NoBuild,

        /** The pack does not take this kind of file from the player, see [takesFromPlayer]. */
        NotTaken,
    }

    sealed interface Verdict {
        /** [build] is what an install would put in, the same build the install picks. */
        data class Fits(val build: ModrinthVersion) : Verdict

        /** The pack already carries the project, as [entry]. */
        data class Present(val entry: InstalledIndex.Entry) : Verdict

        data class NotFit(val reason: Unfit) : Verdict

        /** The pack's folder could not be read or identified, so nothing is known about it. */
        data object Unknown : Verdict
    }

    data class Candidate(val pack: PackInstance, val destination: ContentDestination.Pack, val verdict: Verdict)

    /**
     * Every pack with its answer for [projectId], packs that fit first, then those
     * that already have it, then the rest. Throws when the project's build list could
     * not be read, which is a question nobody answered rather than a project that
     * fits nowhere.
     *
     * [knownListing] is the project's build list when the caller already holds it,
     * so a screen that needs the list for itself does not ask the catalogue twice.
     */
    suspend fun forProject(projectId: String, knownListing: List<ModrinthVersion>? = null): List<Candidate> = withContext(Dispatchers.IO) {
        val listing = knownListing ?: modrinth.listVersions(projectId)
        val instancesDir = dataDir.resolve(INSTANCES_DIR)
        val packs = repository.list()
        val gate = Semaphore(READ_CONCURRENCY)
        coroutineScope {
            packs.map { pack ->
                async {
                    val destination = ContentDestination.Pack.of(pack, instancesDir)
                    Candidate(pack, destination, gate.withPermit { verdictFor(projectId, listing, destination) })
                }
            }.awaitAll()
        }.sortedBy { order(it.verdict) }
    }

    private suspend fun verdictFor(
        projectId: String,
        listing: List<ModrinthVersion>,
        destination: ContentDestination.Pack,
    ): Verdict {
        val snapshot = try {
            index.readAgainst(destination.dir, listing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not read {}: {}", destination.dir, e.message)
            return Verdict.Unknown
        }
        snapshot.of(projectId).firstOrNull()?.let { return Verdict.Present(it) }
        // A file that could not be seen or hashed could be a build of this very
        // project, so the pack is not called a fit.
        if (!snapshot.complete) return Verdict.Unknown
        val build = chooseBuild(listing, destination.mcVersion, destination.loader)
            ?: return Verdict.NotFit(Unfit.NoBuild)
        val kind = (build.placementIn(destination.loader, destination.mcVersion) as Placement.Into).kind
        val keepsRecord = PackPlacedContent.paths(destination.dir) != null
        if (!takesFromPlayer(destination.origin, kind, keepsRecord)) return Verdict.NotFit(Unfit.NotTaken)
        return Verdict.Fits(build)
    }

    private fun order(v: Verdict): Int = when (v) {
        is Verdict.Fits -> 0
        is Verdict.Present -> 1
        is Verdict.NotFit -> 2
        Verdict.Unknown -> 3
    }

    private companion object {
        const val INSTANCES_DIR = "instances"

        /** Packs read at once. Each reading lists a folder and hashes what the hash cache has not seen. */
        const val READ_CONCURRENCY = 4
    }
}
