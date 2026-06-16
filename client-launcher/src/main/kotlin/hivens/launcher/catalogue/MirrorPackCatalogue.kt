package hivens.launcher.catalogue

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.launcher.smrt.SmrtPackClient

/**
 * The Hivens mirror as a [IPackCatalogueService]. The mirror has no query
 * endpoint, so [search] lists everything and filters client-side. A mirror pack
 * exposes a single current version (`latest_pack_version`); the install flow
 * re-syncs to it rather than downloading a single artifact.
 */
class MirrorPackCatalogue(private val client: SmrtPackClient) : IPackCatalogueService {
    override val origin = PackOrigin.Mirror

    override suspend fun search(query: String, page: Int): List<CataloguePack> =
        client.listPacks().packs
            .filter {
                query.isBlank() ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.tagline.contains(query, ignoreCase = true)
            }
            .map { s ->
                CataloguePack(
                    origin = origin,
                    id = s.packId,
                    title = s.displayName,
                    tagline = s.tagline,
                    iconUrl = s.iconUrl,
                    tags = s.tags,
                    mcVersion = s.minecraftVersion,
                )
            }

    override suspend fun details(packId: String): CataloguePackDetails {
        val s = client.fetchSummary(packId)
        return CataloguePackDetails(
            origin = origin,
            id = s.packId,
            title = s.displayName,
            tagline = s.tagline,
            iconUrl = s.iconUrl,
            bannerUrl = s.bannerUrl,
            galleryUrls = s.galleryUrls,
            bodyMarkdown = s.descriptionMd,
            versions = listOf(versionOf(s)),
        )
    }

    override suspend fun versions(packId: String): List<CataloguePackVersion> =
        listOf(versionOf(client.fetchSummary(packId)))

    private fun versionOf(s: SmrtPackSummary) = CataloguePackVersion(
        id = s.latestPackVersion,
        name = s.latestPackVersion,
        versionNumber = s.latestPackVersion,
        mcVersions = listOf(s.minecraftVersion),
    )
}
