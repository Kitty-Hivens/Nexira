package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.modrinth.acceptedLoaders

/**
 * One installed file, named the way every layer here refers to it: what folder
 * it lives in and what it is called. The scan, the update check and the replace
 * all speak this, so a file cannot be matched to the wrong row by a bare string.
 */
data class ContentRef(val kind: ContentKind, val fileName: String)

/**
 * The release channels an update may be drawn from, widest-first as a ladder.
 *
 * A mod that publishes releases must not be handed an alpha merely because the
 * alpha is newer, so the check asks for releases first and only widens for the
 * files nothing came back for. The same ladder Modrinth's own launcher walks,
 * and for the same reason: mods on a beta-only or alpha-only release train are
 * common enough that a release-only check reports "everything is current" for a
 * folder full of them.
 */
enum class ModUpdateChannel(val rungs: List<List<String>>) {
    Release(listOf(listOf("release"), listOf("beta"), listOf("alpha"))),
    Beta(listOf(listOf("release", "beta"), listOf("alpha"))),
    Alpha(listOf(listOf("release", "beta", "alpha"))),
}

/** An available newer file for something already installed. */
data class ModUpdate(
    val ref: ContentRef,
    /** What the archive itself declared, for the "from" half of the label. */
    val installedVersion: String?,
    val projectId: String,
    val versionId: String,
    val versionNumber: String,
    val versionType: String,
    /** The file name the update lands under; usually differs from [ContentRef.fileName]. */
    val fileName: String,
    val url: String,
    val sha1: String,
    val sizeBytes: Long,
    /** When the build was published (ISO-8601, UTC), for comparing it with a pinned one. */
    val datePublished: String = "",
)

/**
 * Whether [candidates] hold an actual update for a file whose sha1 is
 * [installedSha1], and which one.
 *
 * Three rules, all load-bearing:
 *
 *  - The newest by publish date wins, ranked here rather than trusted from the
 *    response: the order is not part of the API contract.
 *  - An answer whose primary file IS the installed file is not an update. The
 *    endpoint answers "the newest version matching this instance", and for
 *    anything already current that is the file that was asked about -- taking
 *    every answer at face value would offer to re-download the whole folder and
 *    call it an update.
 *  - An answer published BEFORE the installed file is not an update either, and
 *    this is the rule that is easy to miss. Ask the release channel about a
 *    machine running a beta and the honest answer is a release from a year ago:
 *    a different hash, and older. Without [installedPublishedAt] that rollback
 *    is offered as an upgrade, once per check, for every mod on a prerelease
 *    build. Null means the file is unknown to Modrinth and there is no date to
 *    compare against, so the hash alone decides.
 *
 * A version with no files at all is skipped rather than crashing the check: one
 * malformed project must not cost the other ninety-nine their answer.
 */
fun updateFrom(
    ref: ContentRef,
    installedSha1: String,
    installedVersion: String?,
    candidates: List<ModrinthVersion>,
    installedPublishedAt: String? = null,
): ModUpdate? {
    val newest = candidates
        .filter { it.files.isNotEmpty() }
        .maxByOrNull { it.datePublished }
        ?: return null
    val file = newest.primaryFile()
    if (file.hashes.sha1.equals(installedSha1, ignoreCase = true)) return null
    // ISO-8601 in UTC, so lexicographic order is chronological order.
    if (installedPublishedAt != null && newest.datePublished <= installedPublishedAt) return null
    return ModUpdate(
        ref              = ref,
        installedVersion = installedVersion,
        projectId        = newest.projectId,
        versionId        = newest.id,
        versionNumber    = newest.versionNumber,
        versionType      = newest.versionType,
        fileName         = file.filename,
        url              = file.url,
        sha1             = file.hashes.sha1,
        sizeBytes        = file.size,
        datePublished    = newest.datePublished,
    )
}

/**
 * The channel a check should ask about for a file, given what the instance
 * prefers and what the file actually IS.
 *
 * A player on a beta gets asked about betas. The preference is a floor, not a
 * ceiling: someone who installed a prerelease build by hand has already said
 * which channel they are on for that mod, and holding them to the stabler
 * setting means either silence or, worse, an offer to move them backwards onto
 * the newest release.
 */
fun effectiveChannel(preferred: ModUpdateChannel, installedType: String?): ModUpdateChannel =
    when (installedType?.lowercase()) {
        "alpha" -> ModUpdateChannel.Alpha
        "beta"  -> if (preferred == ModUpdateChannel.Alpha) preferred else ModUpdateChannel.Beta
        else    -> preferred
    }

/**
 * The same swap an update describes, for a version somebody picked by hand.
 *
 * Choosing a version off the project's list is the identical operation with a
 * different way of arriving at the target -- fetch, verify against the published
 * hash, put it where the old file was -- so it becomes one of these rather than
 * a second install path with its own idea of what a successful swap looks like.
 * Older, newer and the same number all work: the picker offers rollbacks too.
 */
fun ModrinthVersion.swapFor(ref: ContentRef, installedVersion: String?): ModUpdate? {
    if (files.isEmpty()) return null
    val file = primaryFile()
    return ModUpdate(
        ref              = ref,
        installedVersion = installedVersion,
        projectId        = projectId,
        versionId        = id,
        versionNumber    = versionNumber,
        versionType      = versionType,
        fileName         = file.filename,
        url              = file.url,
        sha1             = file.hashes.sha1,
        sizeBytes        = file.size,
        datePublished    = datePublished,
    )
}

/**
 * The dependencies of [version] that actually have to be fetched.
 *
 * Required only: `optional` is the author suggesting a companion mod, and
 * acting on a suggestion grows somebody's folder with things they did not pick.
 * `embedded` is already inside the jar, and `incompatible` is the opposite of a
 * thing to install.
 *
 * [present] is the set of project ids the instance already carries. A
 * dependency already there is not fetched again here: replacing a working build
 * because a newer one exists is the behaviour that took Sodium out from under
 * Iris. One the author pinned to a build newer than the installed one is a
 * different case, and [pinnedRequirements] with [isBehind] is how it is caught.
 */
fun requiredDependencies(
    version: ModrinthVersion,
    present: Set<String>,
): List<ModrinthDependency> = version.dependencies.filter { dep ->
    dep.dependencyType == "required" &&
        (dep.projectId != null || dep.versionId != null) &&
        dep.projectId !in present
}

/**
 * The required dependencies that name an exact build.
 *
 * Modrinth has no version ranges. A pin is the only way an author says which
 * build of a dependency theirs was made against, so it is the only thing a
 * dependency already installed can be held to.
 */
fun pinnedRequirements(dependencies: List<ModrinthDependency>): List<ModrinthDependency> =
    dependencies.filter { it.dependencyType == "required" && it.projectId != null && it.versionId != null }

/**
 * Whether [installed] is an older build than [pinned], the one a dependent was
 * made against. Newer is left alone: a mod built against 4.0 runs on 4.1, and
 * stepping a dependency backwards would break whatever else needed the newer one.
 * ISO-8601 in UTC, so lexicographic order is chronological order.
 */
fun isBehind(installed: ModrinthVersion, pinned: ModrinthVersion): Boolean =
    installed.id != pinned.id && installed.datePublished < pinned.datePublished

/**
 * Which loader ids to ask about for a folder.
 *
 * A mod is published for a loader the instance runs, see [acceptedLoaders]. A
 * resource pack is published for `minecraft`, or for `canvas` or `vanilla` when it
 * is a shader the game reads out of a resource pack, and a shader pack for `iris`
 * or `optifine`. Ask for a resource pack under `neoforge` and Modrinth correctly
 * answers that there is nothing, which reads on screen as "no updates" for a
 * folder that has them.
 */
fun loadersFor(kind: ContentKind, loader: String, mcVersion: String = ""): List<String> = when (kind) {
    ContentKind.Mod          -> acceptedLoaders(loader, mcVersion)
    ContentKind.ResourcePack -> listOf("minecraft", "canvas", "vanilla")
    ContentKind.ShaderPack   -> listOf("iris", "optifine")
}
