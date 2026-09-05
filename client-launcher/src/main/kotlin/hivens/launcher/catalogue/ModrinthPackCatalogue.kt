package hivens.launcher.catalogue

import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.core.update.VersionChannel
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
                bannerUrl = hit.featuredGallery ?: hit.gallery.firstOrNull(),
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
            // Full-res (raw_url) for the hero + lightbox; `url` is a 350px thumbnail
            // that upscales to mush at full-window size. Featured image is the hero,
            // else the first.
            bannerUrl = (p.gallery.firstOrNull { it.featured } ?: p.gallery.firstOrNull())?.let { it.rawUrl ?: it.url },
            // Full-res for the lightbox, the ~350px preview for the grid, and the
            // author's own caption for both.
            gallery = p.gallery.map { g ->
                CatalogueGalleryItem(
                    full = g.rawUrl ?: g.url,
                    thumb = g.url,
                    title = g.title?.takeIf { it.isNotBlank() },
                    description = g.description?.takeIf { it.isNotBlank() },
                )
            },
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
                channel = VersionChannel.of(v.versionType, v.versionNumber),
                publishedAt = v.datePublished.ifBlank { null },
                changelog = v.changelog?.takeIf { it.isNotBlank() },
            )
        }

    private companion object {
        const val PAGE_SIZE = 40
    }
}
