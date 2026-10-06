package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.util.sha1Of
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
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
 * Only `required` dependencies are followed. Optional means the author suggests
 * it, and acting on a suggestion would quietly grow the folder by things the
 * player did not ask for. A dependency already present is left alone unless the
 * author pinned a build of it and the one installed is older: the pack is the
 * player's, and a working build is not ours to replace for being old, only for
 * being older than what the mod was made against.
 */
class ModInstaller(
    private val modrinth: ModrinthClient,
    private val scanner: InstanceContentScanner,
    private val manager: InstanceContentManager = InstanceContentManager(),
) {

    private val log = LoggerFactory.getLogger(ModInstaller::class.java)

    /**
     * What an install did. [installed] is the file names that landed, head of the
     * list first, and [upgraded] the names of dependencies brought up to the build
     * a mod pinned. [skipped] and [missing] are PROJECT ids, the first for
     * dependencies already present and the second for required ones with no build
     * for this instance, which is the one case the caller has to show rather than
     * swallow.
     *
     * [present] is every project the instance carries afterwards, the untouched
     * ninety of them included. A browser showing search results needs to know what
     * is already there and not merely what this call added: a dependency pulled in
     * behind the mod that was clicked is installed too, and so is everything the
     * player put in the folder last month.
     */
    data class Outcome(
        val installed: List<String> = emptyList(),
        val present: Set<String> = emptySet(),
        val skipped: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val upgraded: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = installed.isNotEmpty()
    }

    /**
     * Fetch [version] into the instance's mods folder along with its required
     * dependencies, resolved breadth-first.
     *
     * [depth] bounds the walk. A dependency graph is a graph, not a tree: two
     * mods can want the same API, and a malformed one can point at itself. The
     * visited set covers the honest cases and the bound covers the rest.
     */
    suspend fun install(
        instanceDir: Path,
        version: ModrinthVersion,
        mcVersion: String,
        loader: String,
        depth: Int = MAX_DEPTH,
    ): Outcome = withContext(Dispatchers.IO) {
        val dir = instanceDir.resolve(ContentKind.Mod.folderName())
        Files.createDirectories(dir)

        // What the folder already holds, by project. Resolved by hash, so a jar
        // renamed by hand still counts as installed.
        val held = installedProjects(instanceDir)
        val present = held.keys.toMutableSet()

        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val upgraded = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        var frontier = listOf(version)
        var level = 0
        while (frontier.isNotEmpty() && level <= depth) {
            val next = mutableListOf<ModrinthVersion>()
            for (v in frontier) {
                if (!seen.add(v.id)) continue
                if (level > 0 && v.projectId in present) {
                    skipped += v.projectId
                    continue
                }
                if (!fetch(dir, v)) continue
                installed += v.primaryFile().filename
                present += v.projectId

                // Present already: kept, unless the build just fetched pinned a newer
                // one, which is then put in its place.
                val presentDeps = v.dependencies.filter { it.dependencyType == "required" && it.projectId in present }
                val raised = pinnedUpgrades(presentDeps, held)
                for (target in raised) {
                    if (swapIn(modrinth, manager, instanceDir, target)) upgraded += target.update.fileName
                }
                val raisedIds = raised.mapTo(mutableSetOf()) { it.update.projectId }
                skipped += presentDeps.mapNotNull { it.projectId }.filterNot { it in raisedIds }
                for (dep in requiredDependencies(v, present)) {
                    val projectId = dep.projectId
                    val resolved = resolveDependency(dep.versionId, projectId, mcVersion, loader)
                    if (resolved == null) {
                        // Named rather than dropped: a required dependency with no
                        // build for this game version is why the pack will not
                        // start, and the player has to hear it now.
                        projectId?.let { missing += it }
                        continue
                    }
                    next += resolved
                }
            }
            frontier = next
            level++
        }
        Outcome(installed, present.toSet(), skipped.distinct(), missing.distinct(), upgraded.distinct())
    }

    /** One project the instance carries: the row it is, the build it is, and whether it is on. */
    data class Installed(val ref: ContentRef, val version: ModrinthVersion, val enabled: Boolean)

    /**
     * The mods the instance carries that Modrinth knows, by project id.
     *
     * A file Modrinth has never indexed is absent, see [presentProjects].
     */
    suspend fun installedProjects(instanceDir: Path): Map<String, Installed> = withContext(Dispatchers.IO) {
        val items = runCatching { scanner.scan(instanceDir) }.getOrDefault(emptyList())
            .filter { it.kind == ContentKind.Mod }
        val hashed = items.mapNotNull { c -> runCatching { c to sha1Of(c.pathIn(instanceDir)) }.getOrNull() }
        val versions = runCatching { modrinth.versionsForHashes(hashed.map { it.second }) }.getOrDefault(emptyMap())
        hashed.mapNotNull { (c, hash) ->
            versions[hash]?.let { v -> v.projectId to Installed(ContentRef(c.kind, c.fileName), v, c.enabled) }
        }.toMap()
    }

    /**
     * The [dependencies] that pin a build newer than the one the instance has, as
     * swaps to the pinned build in the state the installed file is in.
     *
     * Only pins are acted on, see [pinnedRequirements]. A dependency the instance
     * does not carry is not this function's business, and neither is one whose
     * pinned build cannot be fetched: that one is logged and left, since the
     * installed build may well run.
     */
    suspend fun pinnedUpgrades(instanceDir: Path, dependencies: List<ModrinthDependency>): List<InstanceContentUpdater.Target> =
        pinnedUpgrades(dependencies, installedProjects(instanceDir))

    private suspend fun pinnedUpgrades(
        dependencies: List<ModrinthDependency>,
        held: Map<String, Installed>,
    ): List<InstanceContentUpdater.Target> {
        val out = LinkedHashMap<String, InstanceContentUpdater.Target>()
        for (dep in pinnedRequirements(dependencies)) {
            val projectId = dep.projectId ?: continue
            val versionId = dep.versionId ?: continue
            val have = held[projectId] ?: continue
            if (projectId in out) continue
            val pinned = try {
                modrinth.resolveVersion(projectId, versionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("resolving the pinned {}/{} failed: {}", projectId, versionId, e.message)
                continue
            }
            if (!isBehind(have.version, pinned)) continue
            log.info("{} is on {}, older than the {} a dependent pins", have.ref.fileName, have.version.versionNumber, pinned.versionNumber)
            pinned.swapFor(have.ref, have.version.versionNumber)?.let { out[projectId] = InstanceContentUpdater.Target(it, have.enabled) }
        }
        return out.values.toList()
    }

    /**
     * Project ids the instance already carries, resolved by file hash.
     *
     * Public because the browser asks the same question before a single install
     * has happened: a result it already has must not be offered as if it were
     * new. One implementation, so the browser and the walk below cannot disagree
     * about what "already installed" means.
     *
     * A file Modrinth has never indexed -- anything from CurseForge, anything
     * built by hand -- has no project id and is silently absent from this set.
     * It is the honest answer to the question asked, and the reason a jar from
     * elsewhere still reads as installable.
     */
    suspend fun presentProjects(instanceDir: Path): Set<String> = installedProjects(instanceDir).keys

    /**
     * The exact build when the author pinned one, otherwise the newest that fits
     * this instance. A pin is the author saying these two builds go together --
     * ignoring it is how Iris ends up beside a Sodium it cannot read.
     */
    private suspend fun resolveDependency(
        versionId: String?,
        projectId: String?,
        mcVersion: String,
        loader: String,
    ): ModrinthVersion? = try {
        when {
            versionId != null && projectId != null -> modrinth.resolveVersion(projectId, versionId)
            projectId != null -> modrinth.newestMatchingVersion(projectId, mcVersion, loader)
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("resolving dependency {}/{} failed: {}", projectId, versionId, e.message)
        null
    }

    /** Download one version's primary file; a name already in the folder is left alone. */
    private suspend fun fetch(dir: Path, v: ModrinthVersion): Boolean {
        val file = v.files.firstOrNull { it.primary } ?: v.files.firstOrNull() ?: return false
        return try {
            modrinth.downloadTo(file.url, dir.resolve(file.filename), file.hashes.sha1)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("downloading {} failed: {}", file.filename, e.message)
            false
        }
    }

    private companion object {
        /**
         * How deep the walk goes. Three levels covers a mod wanting an API that
         * wants a core library; past that a pack is describing something other
         * than a dependency chain.
         */
        const val MAX_DEPTH = 3
    }
}
