package hivens.ui.feature.catalogue.project

import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.modrinth.ModrinthVersion

/**
 * One build of a project or of a pack, as the versions table and the changelog
 * read it.
 *
 * Both sources already speak in the catalogue's version-object terms, the mirror
 * on purpose, so a build from either maps across field for field and the two
 * pages draw one table and one changelog instead of two copies that drift. A
 * field a source does not publish stays null, and the column or the line for it
 * is left out rather than filled with a zero that reads as a count.
 */
data class ProjectBuild(
    val id: String,
    val name: String,
    val versionNumber: String,
    /** `release` / `beta` / `alpha`. */
    val versionType: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    /** RFC 3339, or blank where the source does not date the build. */
    val datePublished: String,
    val downloads: Long? = null,
    val changelog: String? = null,
    /** What the build ships, for the files row under it. Empty where the source does not list files. */
    val files: List<BuildFile> = emptyList(),
    val modsCount: Int? = null,
    val sizeBytes: Long? = null,
) {
    /** The name a row shows: the number where there is one. */
    val label: String get() = versionNumber.ifBlank { name }
}

/** One file of a build. [primary] is the one an install takes. */
data class BuildFile(val filename: String, val size: Long, val primary: Boolean)

fun ModrinthVersion.toBuild(): ProjectBuild = ProjectBuild(
    id = id,
    name = name,
    versionNumber = versionNumber,
    versionType = versionType,
    gameVersions = gameVersions,
    loaders = loaders,
    datePublished = datePublished,
    downloads = downloads,
    changelog = changelog,
    files = files.map { BuildFile(it.filename, it.size, it.primary) },
)

fun CataloguePackVersion.toBuild(): ProjectBuild = ProjectBuild(
    id = id,
    name = name,
    versionNumber = versionNumber,
    versionType = channel.name.lowercase(),
    gameVersions = mcVersions,
    loaders = loaders,
    datePublished = publishedAt.orEmpty(),
    downloads = downloads,
    changelog = changelog,
    modsCount = modsCount,
    sizeBytes = sizeBytes,
)
