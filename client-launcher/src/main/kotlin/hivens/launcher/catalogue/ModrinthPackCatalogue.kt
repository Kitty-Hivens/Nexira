package hivens.launcher.catalogue

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.launcher.modrinth.ModrinthClient

/**
 * Modrinth as a browsable [IPackCatalogueService]: modpack search, the project
 * page (body + gallery), and the version list whose primary file is the
 * installable `.mrpack`.
 */
class ModrinthPackCatalogue(private val client: ModrinthClient) : IPackCatalogueService {
    override val origin = PackOrigin.Modrinth

    override suspend fun search(query: String, page: Int): List<CataloguePack> =
        client.searchModpacks(query, offset = page * PAGE_SIZE, limit = PAGE_SIZE).hits.map { hit ->
            CataloguePack(
                origin = origin,
                id = hit.projectId,
                title = hit.title.ifBlank { hit.slug },
                tagline = hit.description,
                iconUrl = hit.iconUrl,
                tags = hit.categories,
            )
        }

    override suspend fun details(packId: String): CataloguePackDetails {
        val p = client.resolveProject(packId)
        return CataloguePackDetails(
            origin = origin,
            id = p.id,
            title = p.title,
            tagline = p.description,
            iconUrl = p.iconUrl,
            // Featured gallery image is the hero; fall back to the first.
            bannerUrl = p.gallery.firstOrNull { it.featured }?.url ?: p.gallery.firstOrNull()?.url,
            galleryUrls = p.gallery.map { it.url },
            bodyMarkdown = p.body.ifBlank { null },
            versions = versions(packId),
        )
    }

    override suspend fun versions(packId: String): List<CataloguePackVersion> =
        client.listVersions(packId).map { v ->
            CataloguePackVersion(
                id = v.id,
                name = v.name,
                versionNumber = v.versionNumber,
                mcVersions = v.gameVersions,
                loaders = v.loaders,
                // A modpack version's primary file is its .mrpack.
                downloadUrl = v.files.firstOrNull { it.primary }?.url ?: v.files.firstOrNull()?.url,
            )
        }

    private companion object {
        const val PAGE_SIZE = 40
    }
}
