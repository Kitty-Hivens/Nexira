package hivens.launcher.catalogue

import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.inLanguage
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.launcher.smrt.SmrtPackClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * The Hivens mirror as a [IPackCatalogueService]. The mirror has no query
 * endpoint, so [search] lists everything and filters client-side. [details]
 * carries the full retained build list so the Browse install picker can offer
 * any version -- the coordinator installs the picked build's own manifest --
 * degrading to the single latest when the listing is unavailable.
 *
 * Not wrapped in [CachedPackCatalogue]: a search is a filter over the listing,
 * which [SmrtPackClient] already keeps on disk, and a second cache over it took
 * the inner one's stale answer as a fresh one of its own.
 *
 * The mirror carries its text per language beside the untagged copy. It is
 * picked here, where the wire shape becomes the catalogue's, by [language]: the
 * reader's tag, asked on every read so a change of interface language reaches
 * the next answer. The catalogue's models keep one string per field, and no
 * screen that draws them has to know there were several.
 */
class MirrorPackCatalogue(
    private val client: SmrtPackClient,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val language: () -> String = { "" },
) : IPackCatalogueService {
    private val log = LoggerFactory.getLogger(MirrorPackCatalogue::class.java)

    override val origin = PackOrigin.Mirror

    override val paged = false

    override suspend fun search(query: String, page: Int): List<CataloguePack> =
        matching(client.listPacks(), query)

    /**
     * The stored listing at once, then the mirror asked again every
     * [pollIntervalMs] for as long as the screen collects, and a new list only
     * when the answer differs.
     *
     * The listing is the one call nothing else refreshes: a pack published on the
     * mirror stayed out of Browse until the stored copy expired, and a restart
     * did not help, because the copy is on disk. A failed poll keeps what is
     * shown and asks again on the next one. Only the first answer can fail the
     * stream, since before it there is nothing to keep.
     */
    override fun searchStream(query: String, page: Int): Flow<List<CataloguePack>> = flow {
        var last: List<CataloguePack>? = null
        client.packsStream().collect { listing ->
            val packs = matching(listing, query)
            if (packs != last) {
                last = packs
                emit(packs)
            }
        }
        while (true) {
            delay(pollIntervalMs)
            val listing = try {
                client.listPacks(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("mirror listing poll failed, keeping the shown list", e)
                continue
            }
            val packs = matching(listing, query)
            if (packs != last) {
                log.info("mirror listing changed: {} pack(s) for \"{}\"", packs.size, query)
                last = packs
                emit(packs)
            }
        }
    }

    private fun matching(listing: SmrtPackListing, query: String): List<CataloguePack> {
        val tag = language()
        return listing.packs
            .map { it to taglineOf(it, tag) }
            .filter { (s, tagline) ->
                query.isBlank() ||
                    s.displayName.contains(query, ignoreCase = true) ||
                    tagline.contains(query, ignoreCase = true)
            }
            .map { (s, tagline) ->
                CataloguePack(
                    origin = origin,
                    id = s.packId,
                    title = s.displayName,
                    tagline = tagline,
                    iconUrl = s.iconUrl,
                    bannerUrl = s.bannerUrl,
                    tags = s.tags,
                    mcVersion = s.minecraftVersion,
                )
            }
    }

    private fun taglineOf(s: SmrtPackSummary, tag: String): String = inLanguage(s.tagline, s.taglineI18n, tag) ?: s.tagline

    override suspend fun details(packId: String): CataloguePackDetails = coroutineScope {
        // Summary + manifest + build listing in parallel: the manifest carries
        // loader + Java for the metadata block, the listing feeds the version
        // picker (server order, newest first).
        val summaryD = async { client.fetchSummary(packId) }
        val manifestD = async { client.fetchManifest(packId) }
        val buildsD = async { runCatching { client.listBuilds(packId).builds }.getOrDefault(emptyList()) }
        val s = summaryD.await()
        val m = manifestD.await()
        val tag = language()
        val versions = buildsD.await()
            .map { it.forLanguage(tag) }
            .map { b ->
                CataloguePackVersion(
                    id = b.versionNumber,
                    name = b.versionNumber,
                    versionNumber = b.versionNumber,
                    mcVersions = listOf(m.minecraft.version),
                    loaders = listOf(m.loader.name),
                    channel = b.channel,
                    publishedAt = b.datePublished,
                    changelog = b.changelog,
                )
            }
            .ifEmpty { listOf(versionOf(s, m)) }
        CataloguePackDetails(
            origin = origin,
            id = s.packId,
            title = s.displayName,
            tagline = taglineOf(s, tag),
            iconUrl = s.iconUrl,
            bannerUrl = s.bannerUrl,
            // The mirror publishes URLs and no captions, so one size serves both
            // the grid and the lightbox and the shots go uncaptioned.
            gallery = s.galleryUrls.map { CatalogueGalleryItem(full = it, thumb = it) },
            bodyMarkdown = inLanguage(s.descriptionMd, s.descriptionMdI18n, tag),
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
        val tag = language()
        val builds = buildsD.await().map { it.forLanguage(tag) }
        if (builds.isEmpty()) listOf(versionOf(s, null))
        else builds.map { b ->
            CataloguePackVersion(
                id = b.versionNumber,
                name = b.versionNumber,
                versionNumber = b.versionNumber,
                mcVersions = listOf(s.minecraftVersion),
                loaders = emptyList(),
                channel = b.channel,
                publishedAt = b.datePublished,
                changelog = b.changelog,
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

    companion object {
        /** How often an open Browse asks the mirror whether its listing changed. */
        const val POLL_INTERVAL_MS = 60_000L
    }
}
