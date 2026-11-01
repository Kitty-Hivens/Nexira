package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Installs a Modrinth version into an instance, and the things it needs to run.
 *
 * A mod is rarely one file. Ars Nouveau wants Curios and Patchouli; half the
 * tech mods want an API jar nobody has heard of. Fetching only what was clicked
 * leaves the player with a pack that dies on the loading screen naming a mod
 * they never chose, which is the worst possible moment to learn about a
 * dependency -- and the reason "install" here means "install this, and what it
 * cannot run without".
 *
 * The work is [ContentInstaller]'s. This is its face for callers that address an
 * instance by its folder and read a flat [Outcome], which is every caller there
 * is today. It finds the pack that folder belongs to, since the install has to
 * know where the pack came from and has to mark the pack busy while it runs.
 */
class ModInstaller(
    private val core: ContentInstaller,
    private val index: InstalledIndex,
    private val repository: IPackRepository,
    /** The launcher's data directory, whose `instances/` holds every pack's folder. */
    private val dataDir: Path,
) {

    private val log = LoggerFactory.getLogger(ModInstaller::class.java)

    /**
     * What an install did. [installed] is the file names that landed, head of the
     * list first; [skipped] and [missing] are PROJECT ids, the first for
     * dependencies already present and the second for required ones that did not
     * come with it, which is the one case the caller has to show rather than
     * swallow.
     *
     * The build asked for counts as installed when the pack already holds its very
     * bytes: what was asked for is what the pack has.
     *
     * [present] is every project the instance carries afterwards, the untouched
     * ninety of them included. A browser showing search results needs to know what
     * is already there and not merely what this call added: a dependency pulled in
     * behind the mod that was clicked is installed too, and so is everything the
     * player put in the folder last month.
     *
     * [skips] is every project the install left out with the reason, for a screen
     * that names them rather than counting them. [refusal] says why nothing ran at
     * all, and is null when the install ran. [headLeftOut] is why the build asked for
     * was left out when the install could say why, a reason a retry does not change,
     * and null when it landed, was already there, or its download broke.
     */
    data class Outcome(
        val installed: List<String> = emptyList(),
        val present: Set<String> = emptySet(),
        val skipped: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val skips: List<ContentInstaller.Skip> = emptyList(),
        val refusal: ContentInstaller.Refusal? = null,
        val headLeftOut: ContentInstaller.Skip? = null,
    ) {
        val ok: Boolean get() = installed.isNotEmpty()
    }

    /**
     * Fetch [version] into the instance at [instanceDir] along with its required
     * dependencies, [depth] levels down. [mcVersion] and [loader] are what the
     * caller read off the pack, and they decide what fits.
     */
    suspend fun install(
        instanceDir: Path,
        version: ModrinthVersion,
        mcVersion: String,
        loader: String,
        depth: Int = ContentInstaller.MAX_DEPTH,
    ): Outcome {
        val pack = destinationOf(instanceDir, mcVersion, loader) ?: run {
            log.warn("not installing {}: no pack lives in {}", version.id, instanceDir)
            return Outcome()
        }
        return when (val result = core.install(pack, version, depth)) {
            is ContentInstaller.Result.Refused -> {
                log.info("not installing {} into {}: {}", version.id, instanceDir, result.reason)
                Outcome(refusal = result.reason)
            }
            is ContentInstaller.Result.Done -> outcomeOf(result, version.projectId)
        }
    }

    /** One project the instance carries: the row it is, the build it is, and whether it is on. */
    data class Installed(val ref: ContentRef, val version: ModrinthVersion, val enabled: Boolean)

    /**
     * The content the instance carries that Modrinth knows, by project id, from
     * every content folder.
     *
     * A file Modrinth has never indexed is absent, see [presentProjects].
     */
    suspend fun installedProjects(instanceDir: Path): Map<String, Installed> =
        index.read(instanceDir).entries
            .mapNotNull { e -> e.version?.let { v -> v.projectId to Installed(e.ref, v, e.enabled) } }
            .toMap()

    /**
     * Project ids the instance already carries, resolved by file hash.
     *
     * Public because the browser asks the same question before a single install
     * has happened: a result it already has must not be offered as if it were
     * new. One implementation, so the browser and the install cannot disagree
     * about what "already installed" means.
     *
     * A file Modrinth has never indexed -- anything from CurseForge, anything
     * built by hand -- has no project id and is silently absent from this set.
     * It is the honest answer to the question asked, and the reason a jar from
     * elsewhere still reads as installable.
     */
    suspend fun presentProjects(instanceDir: Path): Set<String> = index.read(instanceDir).projects

    /** The pack whose folder is [instanceDir], with the runtime the caller read off it. */
    private suspend fun destinationOf(instanceDir: Path, mcVersion: String, loader: String): ContentDestination.Pack? {
        val instancesDir = dataDir.resolve(INSTANCES_DIR)
        val wanted = instanceDir.toAbsolutePath().normalize()
        val instance = withContext(Dispatchers.IO) {
            repository.list().firstOrNull { instancesDir.resolve(it.instanceDirName).toAbsolutePath().normalize() == wanted }
        } ?: return null
        return ContentDestination.Pack.of(instance, instancesDir).copy(mcVersion = mcVersion, loader = loader)
    }

    private fun outcomeOf(result: ContentInstaller.Result.Done, headProject: String): Outcome {
        val alreadyThere = result.skipped.filterIsInstance<ContentInstaller.Skip.AlreadyInstalled>()
            .filter { it.projectId == headProject }
            .map { it.ref.fileName }
        val notBrought = result.skipped.filter {
            it.projectId != headProject && when (it) {
                is ContentInstaller.Skip.Present, is ContentInstaller.Skip.AlreadyInstalled -> false
                else -> true
            }
        }
        return Outcome(
            installed = alreadyThere + result.landed.map { it.ref.fileName },
            present = result.present,
            skipped = result.skipped.filterIsInstance<ContentInstaller.Skip.Present>().map { it.projectId }.distinct(),
            missing = notBrought.mapNotNull { it.projectId }.distinct(),
            skips = result.skipped,
            headLeftOut = result.skipped.firstOrNull {
                it.projectId == headProject && when (it) {
                    is ContentInstaller.Skip.Present, is ContentInstaller.Skip.AlreadyInstalled, is ContentInstaller.Skip.Failed -> false
                    else -> true
                }
            },
        )
    }

    private companion object {
        const val INSTANCES_DIR = "instances"
    }
}
