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
    /**
     * Both default to empty. Modrinth documents them as optional, and the bulk
     * update response decodes a hundred versions in one pass -- one project that
     * omits a field would otherwise cost the other ninety nine their answer.
     */
    val name: String = "",
    @SerialName("version_number") val versionNumber: String = "",
    /** `release` / `beta` / `alpha` -- the source of update "channels". */
    @SerialName("version_type") val versionType: String = "release",
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("date_published") val datePublished: String = "",
    /** The author's notes for this version; Modrinth serves null for most versions. */
    val changelog: String? = null,
    /**
     * What this version needs beside it. A `required` entry with a
     * [ModrinthDependency.versionId] pins an exact build (Iris does this to
     * Sodium); one with only a project id leaves the choice to the installer.
     */
    val dependencies: List<ModrinthDependency> = emptyList(),
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

/**
 * One declared dependency of a version.
 *
 * [dependencyType] is `required` / `optional` / `incompatible` / `embedded`.
 * Only the first is worth acting on without asking: an optional dependency is
 * the author saying "this works better with", which is a suggestion, and an
 * embedded one is already inside the jar.
 */
@Serializable
data class ModrinthDependency(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("dependency_type") val dependencyType: String = "required",
)

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
