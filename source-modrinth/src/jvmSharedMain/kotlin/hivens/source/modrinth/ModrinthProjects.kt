package hivens.source.modrinth

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.LoaderTag
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectSearcher
import hivens.catalogue.api.SearchFilter
import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import hivens.launcher.modrinth.FilterField as ClientField
import hivens.launcher.modrinth.SearchFilter as ClientFilter

/**
 * What Modrinth answers as a project source: its search, narrowed by the catalogue's
 * filters and ordered by its sorts, and the three lists the filters are built from.
 * The catalogue's filters and project types are Modrinth's own vocabulary, so the
 * mapping is one to one.
 */
class ModrinthProjects(private val client: ModrinthClient) : ProjectSearcher {
    override suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit> =
        client.search(type, query, filters.map { it.toClient() }, offset, limit, sort.index).hits.map { it.toHit() }

    /** The three lists read side by side. */
    override suspend fun tags(): CatalogueTags = coroutineScope {
        val versions = async { client.gameVersions() }
        val categories = async { client.categoryTags() }
        val loaders = async { client.loaderTags() }
        catalogueTags(versions.await(), categories.await(), loaders.await())
    }

    override suspend fun gameVersions(): List<GameVersionTag> = client.gameVersions().map { it.toTag() }
}

internal fun FilterField.toClient(): ClientField = when (this) {
    FilterField.GameVersion -> ClientField.GameVersion
    FilterField.Loader -> ClientField.Loader
    FilterField.Category -> ClientField.Category
    FilterField.Resolution -> ClientField.Resolution
    FilterField.Environment -> ClientField.Environment
    FilterField.OpenSource -> ClientField.OpenSource
    FilterField.Disclosure -> ClientField.Disclosure
    FilterField.Project -> ClientField.Project
}

internal fun SearchFilter.toClient(): ClientFilter = ClientFilter(field.toClient(), value, excluded)

internal fun ModrinthSearchHit.toHit(): ProjectHit = ProjectHit(
    projectId = projectId,
    slug = slug,
    title = title,
    description = description,
    iconUrl = iconUrl,
    categories = categories,
    featuredGallery = featuredGallery,
    gallery = gallery,
    author = author,
    downloads = downloads,
    follows = follows,
    dateModified = dateModified,
    dateCreated = dateCreated,
    displayCategories = displayCategories,
)

internal fun ModrinthGameVersion.toTag(): GameVersionTag = GameVersionTag(version, versionType, major, date)

internal fun ModrinthCategoryTag.toTag(): CategoryTag = CategoryTag(name, projectType, header, icon)

internal fun ModrinthLoaderTag.toTag(): LoaderTag = LoaderTag(name, supportedProjectTypes, icon)

internal fun catalogueTags(versions: List<ModrinthGameVersion>, categories: List<ModrinthCategoryTag>, loaders: List<ModrinthLoaderTag>): CatalogueTags =
    CatalogueTags(versions.map { it.toTag() }, categories.map { it.toTag() }, loaders.map { it.toTag() })
