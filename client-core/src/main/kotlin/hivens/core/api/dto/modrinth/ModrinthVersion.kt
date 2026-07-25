package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal Modrinth `/v2/project/{id}/version/{version_id}` response
 * subset -- just the fields needed to pick the primary file and verify
 * its sha1. Modrinth returns much more (changelog, dependencies,
 * project_type, etc); `ignoreUnknownKeys = true` discards what we
 * don't read.
 */
@Serializable
data class ModrinthVersion(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    @SerialName("version_number") val versionNumber: String,
    /** `release` / `beta` / `alpha` -- the source of update "channels". */
    @SerialName("version_type") val versionType: String = "release",
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("date_published") val datePublished: String = "",
    /** The author's notes for this version; Modrinth serves null for most versions. */
    val changelog: String? = null,
    val files: List<ModrinthFile>,
) {
    /**
     * Pick the file flagged `primary = true`; if none is, fall back to
     * the first entry. Matches the spec rule for source resolution --
     * Modrinth version payloads sometimes ship multiple artifacts
     * (sources jar, deobf, signature) and `files[0]` is not guaranteed
     * to be the installable one.
     */
    fun primaryFile(): ModrinthFile = files.firstOrNull { it.primary } ?: files.first()
}

@Serializable
data class ModrinthFile(
    val hashes: ModrinthHashes,
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long,
)

@Serializable
data class ModrinthHashes(val sha1: String)
