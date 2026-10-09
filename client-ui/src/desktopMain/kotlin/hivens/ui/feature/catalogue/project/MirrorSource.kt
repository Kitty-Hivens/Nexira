package hivens.ui.feature.catalogue.project

import hivens.core.api.dto.smrt.SmrtModDetail
import hivens.launcher.smrt.SmrtPackClient
import kotlinx.coroutines.CancellationException

/**
 * What the mirror's registry says about a file the catalogue could not name.
 *
 * The mirror keeps a page for every jar it has seen, Modrinth's or not, so a mod
 * pinned from CurseForge or a release on GitHub, shipped in one of its packs, has
 * a name, its authors, its releases, what it requires and where else it ships,
 * where the catalogue alone left a file with question marks. [packs] is the
 * mirror's listing, by pack id: the name each pack is listed by and the build it
 * currently serves.
 */
class MirrorMod(
    val detail: SmrtModDetail,
    val sha1: String,
    val iconUrl: String?,
    val packs: Map<String, MirrorPackRef> = emptyMap(),
) {
    /** Where the file itself is published, when the mirror knows of a host beside itself. */
    val publishedOn: String?
        get() = fileOf()?.curseforge?.projectId?.let { CURSEFORGE }

    /** The project's page at the host that publishes it. */
    val links: List<ProjectLink>
        get() = listOfNotNull(
            fileOf()?.curseforge?.projectId?.let {
                ProjectLink(ProjectLinkKind.Page, "https://www.curseforge.com/projects/$it", CURSEFORGE)
            },
        )

    /** What the mod asks for, by name, which the details block lists the way a jar's own list is. */
    val requires: List<String>
        get() = detail.edges.filter { it.dir == OUT && it.kind == REQUIRES }.map { it.otherName }.distinct()

    /**
     * The mirror's packs whose current build ships the mod, by the name they are
     * listed under.
     *
     * Current, because the registry answers with the newest build of each pack it
     * ever saw the mod in, and a pack that dropped the mod three builds ago does
     * not have it. Its build index also keeps links a rebuild has since removed,
     * which named packs that never shipped the mod in any build anyone can fetch.
     * Without the listing there is no telling which build is current, so each
     * build is named with its version instead.
     */
    val usedBy: List<String>
        get() = if (packs.isEmpty()) {
            detail.usedBy.map { "${it.packId} ${it.packVersion}" }.distinct()
        } else {
            detail.usedBy.mapNotNull { u -> packs[u.packId]?.takeIf { it.latest == u.packVersion }?.name }.distinct()
        }

    /** Authors as a list. The registry carries them as the jar wrote them, one string. */
    val authors: List<String>
        get() = detail.author?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    /** The registry's file record for the jar this page is about. */
    private fun fileOf() = detail.releases.asSequence().flatMap { it.files }.firstOrNull { it.sha1 == sha1 }

    /**
     * The mod's releases as the versions table reads builds.
     *
     * A release the registry did not grade reads as a release: a beta is a claim
     * somebody has to make, and nobody made it. The registry dates nothing, so the
     * published column stays empty rather than inventing a day.
     */
    fun builds(): List<ProjectBuild> = detail.releases.map { r ->
        ProjectBuild(
            id = "release:${r.releaseId}",
            name = r.versionNumber,
            versionNumber = r.versionNumber,
            versionType = r.channel.takeIf { it in CHANNELS } ?: RELEASE,
            gameVersions = r.files.flatMap { it.mcVersions }.distinct(),
            loaders = r.files.flatMap { it.targets }.filter { it != ANY_LOADER }.distinct(),
            datePublished = "",
            files = r.files.map { f -> BuildFile(f.filename ?: f.sha1.take(12), f.sizeBytes, primary = f.sha1 == sha1) },
        )
    }

    private companion object {
        const val CURSEFORGE = "CurseForge"
        const val OUT = "out"
        const val REQUIRES = "requires"
        const val RELEASE = "release"
        const val ANY_LOADER = "any"
        val CHANNELS = setOf("release", "beta", "alpha")
    }
}

/** A pack as the mirror lists it: its name, and the build it serves as current. */
data class MirrorPackRef(val name: String, val latest: String)

/** How a page asks the mirror about a file. Null on a page stood up without one. */
fun interface MirrorLookup {
    /** The mod a file with [sha1] belongs to, or null where the mirror has never seen it. */
    suspend fun byFile(sha1: String): MirrorMod?
}

/**
 * The mirror client as a [MirrorLookup].
 *
 * The packs come from the listing the client keeps on disk anyway, and a listing
 * that cannot be read leaves the ids as they are rather than losing the mod's page
 * over them.
 */
fun SmrtPackClient.asMirrorLookup(): MirrorLookup = MirrorLookup { sha1 ->
    val detail = modByFile(sha1) ?: return@MirrorLookup null
    val packs = try {
        listPacks().packs.associate { it.packId to MirrorPackRef(it.displayName, it.latestPackVersion) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }
    MirrorMod(detail, sha1, jarIconUrl(sha1), packs)
}
