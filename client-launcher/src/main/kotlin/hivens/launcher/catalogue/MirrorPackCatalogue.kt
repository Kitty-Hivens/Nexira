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
 * endpoint, so [search] lists everything and filters client-side. [details]
 * carries the full retained build list so the Browse install picker can offer
 * any version -- the coordinator installs the picked build's own manifest --
 * degrading to the single latest when the listing is unavailable.
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
        // Summary + manifest + build listing in parallel: the manifest carries
        // loader + Java for the metadata block, the listing feeds the version
        // picker (server order, newest first).
        val summaryD = async { client.fetchSummary(packId) }
        val manifestD = async { client.fetchManifest(packId) }
        val buildsD = async { runCatching { client.listBuilds(packId).builds }.getOrDefault(emptyList()) }
        val s = summaryD.await()
        val m = manifestD.await()
        val versions = buildsD.await()
            .map { b ->
                CataloguePackVersion(
                    id = b.versionNumber,
                    name = b.versionNumber,
                    versionNumber = b.versionNumber,
                    mcVersions = listOf(m.minecraft.version),
                    loaders = listOf(m.loader.name),
                )
            }
            .ifEmpty { listOf(versionOf(s, m)) }
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
            versions = versions,
        )
    }

    override suspend fun versions(packId: String): List<CataloguePackVersion> = coroutineScope {
        // Summary (for the MC hint) + the retained build listing in parallel. Server
        // order is canonical (publish date ranks across channels; tuples do not). A
        // mirror that has no versions endpoint (or errors) degrades to the single latest.
        val summaryD = async { client.fetchSummary(packId) }
        val buildsD = async { runCatching { client.listBuilds(packId).builds }.getOrDefault(emptyList()) }
        val s = summaryD.await()
        buildsD.await().map { it.versionNumber }.ifEmpty { listOf(s.latestPackVersion) }
            .map { v ->
                CataloguePackVersion(
                    id = v,
                    name = v,
                    versionNumber = v,
                    mcVersions = listOf(s.minecraftVersion),
                    loaders = emptyList(),
                )
            }
    }

    private fun versionOf(s: SmrtPackSummary, m: SmrtPackManifest?) = CataloguePackVersion(
        id = s.latestPackVersion,
        name = s.latestPackVersion,
        versionNumber = s.latestPackVersion,
        mcVersions = listOf(m?.minecraft?.version ?: s.minecraftVersion),
        loaders = m?.loader?.name?.let { listOf(it) } ?: emptyList(),
    )
}
