package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthVersion

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
)

/**
 * Whether [candidates] hold an actual update for a file whose sha1 is
 * [installedSha1], and which one.
 *
 * Two rules, both load-bearing:
 *
 *  - The newest by publish date wins, ranked here rather than trusted from the
 *    response: the order is not part of the API contract.
 *  - An answer whose primary file IS the installed file is not an update. The
 *    endpoint answers "the newest version matching this instance", and for
 *    anything already current that is the file that was asked about -- taking
 *    every answer at face value would offer to re-download the whole folder and
 *    call it an update.
 *
 * A version with no files at all is skipped rather than crashing the check: one
 * malformed project must not cost the other ninety-nine their answer.
 */
fun updateFrom(
    ref: ContentRef,
    installedSha1: String,
    installedVersion: String?,
    candidates: List<ModrinthVersion>,
): ModUpdate? {
    val newest = candidates
        .filter { it.files.isNotEmpty() }
        .maxByOrNull { it.datePublished }
        ?: return null
    val file = newest.primaryFile()
    if (file.hashes.sha1.equals(installedSha1, ignoreCase = true)) return null
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
    )
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
    )
}

/**
 * Which loader ids to ask about for a folder.
 *
 * A mod is published for the loader the instance runs. A resource pack is
 * published for `minecraft`, and a shader for `iris` or `optifine` -- ask for a
 * resource pack under `neoforge` and Modrinth correctly answers that there is
 * nothing, which reads on screen as "no updates" for a folder that has them.
 */
fun loadersFor(kind: ContentKind, loader: String): List<String> = when (kind) {
    ContentKind.Mod          -> listOfNotNull(loader.takeIf { it.isNotBlank() })
    ContentKind.ResourcePack -> listOf("minecraft")
    ContentKind.ShaderPack   -> listOf("iris", "optifine")
}
