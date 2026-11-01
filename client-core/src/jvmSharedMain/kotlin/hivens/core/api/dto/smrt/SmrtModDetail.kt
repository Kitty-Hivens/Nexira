package hivens.core.api.dto.smrt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/mods/{key}`: one mod as the mirror's registry knows it.
 *
 * The registry names every jar it has seen, Modrinth's or not, so this is the page
 * a file gets when the catalogue has never heard of it: its name and authors as the
 * jar declares them, every release with the files under it, what it requires and
 * what it conflicts with, and which of the mirror's published packs ship it.
 * Everything past the name is optional, and the decoder ignores what it does not
 * know, as the mirror's API guide asks.
 */
@Serializable
data class SmrtModDetail(
    @SerialName("mod_id") val modId: Long,
    val name: String,
    val slug: String? = null,
    /** As the jar or the catalogue credits it, comma-separated when several. */
    val author: String? = null,
    val modid: String? = null,
    @SerialName("modrinth_project_id") val modrinthProjectId: String? = null,
    /** The catalogue's environment flags, where the mod has a catalogue identity. */
    @SerialName("client_side") val clientSide: String? = null,
    @SerialName("server_side") val serverSide: String? = null,
    val loaders: List<String> = emptyList(),
    @SerialName("mc_versions") val mcVersions: List<String> = emptyList(),
    val releases: List<SmrtModRelease> = emptyList(),
    val edges: List<SmrtModEdge> = emptyList(),
    /** Published official builds that ship the mod, by pack id and version. */
    @SerialName("used_by") val usedBy: List<SmrtModUse> = emptyList(),
)

/** One release of a mod: its number, its channel, and the files published under it. */
@Serializable
data class SmrtModRelease(
    @SerialName("release_id") val releaseId: Long,
    @SerialName("version_number") val versionNumber: String,
    /** `release` / `beta` / `alpha`, or `unknown` where nobody graded it. */
    val channel: String = "unknown",
    val files: List<SmrtModFile> = emptyList(),
)

/** One file of a release, with the loaders and game versions it was built for. */
@Serializable
data class SmrtModFile(
    val version: String = "",
    /** Loaders the file runs on, `any` for one that runs on all of them. */
    val targets: List<String> = emptyList(),
    @SerialName("mc_versions") val mcVersions: List<String> = emptyList(),
    val sha1: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val filename: String? = null,
    @SerialName("modrinth_project_id") val modrinthProjectId: String? = null,
    val curseforge: SmrtCurseForgeIdentity? = null,
)

/**
 * What CurseForge says a file is, for a file Modrinth does not carry. A null
 * [projectId] is an answer: CurseForge does not publish this file either.
 */
@Serializable
data class SmrtCurseForgeIdentity(
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("file_id") val fileId: Long? = null,
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * One relation touching the mod. [dir] is `out` for what this mod asks of another
 * and `in` for what another asks of it; [kind] is `requires`, `optional_dep`,
 * `conflicts`, `provides` or `recommends`.
 */
@Serializable
data class SmrtModEdge(
    val dir: String,
    @SerialName("other_name") val otherName: String,
    val kind: String,
)

/** A published build that ships the mod. */
@Serializable
data class SmrtModUse(
    @SerialName("pack_id") val packId: String,
    @SerialName("pack_version") val packVersion: String,
)
