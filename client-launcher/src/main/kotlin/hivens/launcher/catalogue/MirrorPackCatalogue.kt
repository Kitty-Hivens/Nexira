package hivens.launcher.catalogue

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.launcher.smrt.SmrtPackClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
                    bannerUrl = s.bannerUrl,
                    tags = s.tags,
                    mcVersion = s.minecraftVersion,
                )
            }

    override suspend fun details(packId: String): CataloguePackDetails = coroutineScope {
        // Summary + manifest in parallel: the manifest carries loader + Java that the
        // detail metadata block shows but the summary alone doesn't.
        val summaryD = async { client.fetchSummary(packId) }
        val manifestD = async { client.fetchManifest(packId) }
        val s = summaryD.await()
        val m = manifestD.await()
        CataloguePackDetails(
            origin = origin,
            id = s.packId,
            title = s.displayName,
            tagline = s.tagline,
            iconUrl = s.iconUrl,
            bannerUrl = s.bannerUrl,
            galleryUrls = s.galleryUrls,
            bodyMarkdown = s.descriptionMd,
            tags = s.tags,
            runtimeLabel = "Java ${m.java.major}",
            versions = listOf(versionOf(s, m)),
        )
    }

    override suspend fun versions(packId: String): List<CataloguePackVersion> =
        listOf(versionOf(client.fetchSummary(packId), null))

    private fun versionOf(s: SmrtPackSummary, m: SmrtPackManifest?) = CataloguePackVersion(
        id = s.latestPackVersion,
        name = s.latestPackVersion,
        versionNumber = s.latestPackVersion,
        mcVersions = listOf(m?.minecraft?.version ?: s.minecraftVersion),
        loaders = m?.loader?.name?.let { listOf(it) } ?: emptyList(),
    )
}
