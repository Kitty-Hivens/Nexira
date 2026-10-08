package hivens.launcher.catalogue

import hivens.core.api.catalogue.CatalogueCreator
import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.smrt.SmrtCommunityPack
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.inLanguage
import hivens.core.api.dto.smrt.toDomain
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.launcher.smrt.SmrtPackClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import org.slf4j.LoggerFactory

/**
 * The Hivens mirror as a [IPackCatalogueService]. The mirror has no query
 * endpoint, so [search] lists everything and filters client-side.
 *
 * Its own packs and its community's are two listings, and one list here: the
 * official ones first, in the mirror's order, then the community's, each with the
 * owner's login. Ranked no other way, because ranking packs by what players do
 * would need the mirror to be told what they do. The community listing is the
 * smaller half of the answer, so a failure there leaves the official packs on
 * screen rather than taking them down with it. [details]
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
        matching(client.listPacks(), communityOrNone(forceRefresh = false), query)

    /**
     * The stored listing at once, then the mirror asked again every
     * [pollIntervalMs] for as long as the screen collects, and a new list only
     * when the answer differs.
     *
     * The listing is the one call nothing else refreshes: a pack published on the
     * mirror stayed out of Browse until the stored copy expired, and a restart
     * did not help, because the copy is on disk. A failed refresh or poll keeps
     * what is shown and asks again on the next one. Only a failure before anything
     * was shown ends the stream, since then there is nothing to keep.
     */
    override fun searchStream(query: String, page: Int): Flow<List<CataloguePack>> = flow {
        var last: List<CataloguePack>? = null
        var community: List<SmrtCommunityPack> = emptyList()
        // The stored listing is handed over before the refresh behind it is asked,
        // so that refresh can fail with a list already on screen. Thrown on from
        // there, it ended the stream before the first poll and the list stayed the
        // stored one for the whole visit. Only a failure with nothing shown yet
        // ends the stream, which the screen then reports with its retry.
        try {
            // The community half joins when it arrives and never holds the official
            // half back: it starts empty, and a failure keeps what it had.
            val communityView = client.communityStream()
                .onStart { emit(emptyList()) }
                .catch { log.debug("mirror community listing unavailable", it) }
            client.packsStream().combine(communityView) { listing, members -> listing to members }.collect { (listing, members) ->
                community = members
                val packs = matching(listing, members, query)
                if (packs != last) {
                    last = packs
                    emit(packs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (last == null) throw e
            log.debug("mirror listing refresh failed, keeping the stored list and polling", e)
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
            community = communityOrNone(forceRefresh = true) ?: community
            val packs = matching(listing, community, query)
            if (packs != last) {
                log.info("mirror listing changed: {} pack(s) for \"{}\"", packs.size, query)
                last = packs
                emit(packs)
            }
        }
    }

    /** The community listing, or null when it could not be read. */
    private suspend fun communityOrNone(forceRefresh: Boolean): List<SmrtCommunityPack>? = try {
        client.listCommunity(forceRefresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("mirror community listing unavailable", e)
        null
    }

    private fun matching(listing: SmrtPackListing, community: List<SmrtCommunityPack>?, query: String): List<CataloguePack> {
        val tag = language()
        val official = listing.packs.map { it to null }
        // A pack listed in both is the mirror's own: a promotion moves it across.
        val ownIds = listing.packs.mapTo(HashSet()) { it.packId }
        val members = community.orEmpty().filter { it.summary.packId !in ownIds }.map { it.summary to it.ownerLogin }
        return (official + members)
            .map { (s, owner) -> Triple(s, owner, taglineOf(s, tag)) }
            .filter { (s, _, tagline) ->
                query.isBlank() ||
                    s.displayName.contains(query, ignoreCase = true) ||
                    tagline.contains(query, ignoreCase = true)
            }
            .map { (s, owner, tagline) ->
                CataloguePack(
                    origin = origin,
                    id = s.packId,
                    title = s.displayName,
                    tagline = tagline,
                    iconUrl = s.iconUrl,
                    bannerUrl = s.bannerUrl,
                    tags = s.tags,
                    mcVersion = s.minecraftVersion,
                    community = owner != null,
                    author = owner,
                )
            }
    }

    private fun taglineOf(s: SmrtPackSummary, tag: String): String = inLanguage(s.tagline, s.taglineI18n, tag) ?: s.tagline

    override suspend fun details(packId: String): CataloguePackDetails = coroutineScope {
        // Summary + manifest + build listing in parallel: the manifest carries
        // loader, Java and the sign-in the pack needs, the listing feeds the
        // versions (server order, newest first) and names the latest build.
        val summaryD = async { client.fetchSummary(packId) }
        val manifestD = async { client.fetchManifest(packId) }
        val listingD = async { runCatching { client.listBuilds(packId) }.getOrNull() }
        val s = summaryD.await()
        // The summary names a community pack's owner by account id only. The
        // login is the community listing's, read from its stored copy as a rule.
        val owner = if (s.tier == COMMUNITY_TIER) {
            communityOrNone(forceRefresh = false)?.firstOrNull { it.summary.packId == packId }?.ownerLogin
        } else {
            null
        }
        val m = manifestD.await()
        val listing = listingD.await()
        val tag = language()
        val versions = listing?.builds.orEmpty()
            .map { it.forLanguage(tag) }
            .map { b ->
                CataloguePackVersion(
                    id = b.versionNumber,
                    name = b.versionNumber,
                    versionNumber = b.versionNumber,
                    // A build that names its own runtime is read for it, so a pack
                    // that moved loader or game version says which build did.
                    mcVersions = listOf(b.minecraftVersion ?: m.minecraft.version),
                    loaders = listOf(b.loaderName ?: m.loader.name),
                    channel = b.channel,
                    publishedAt = b.datePublished,
                    changelog = b.changelog,
                    modsCount = b.modsCount,
                    sizeBytes = b.sizeBytes,
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
            gameVersions = versions.flatMap { it.mcVersions }.distinct(),
            loaders = versions.flatMap { it.loaders }.distinct(),
            updatedAt = s.latestBuiltAt,
            // The pointer the mirror serves as current, which a curator may have
            // left on a beta; the listing's newest is not necessarily it.
            latestVersionId = listing?.latest ?: s.latestPackVersion,
            auth = m.auth?.toDomain(),
            // No avatar: the only picture of a member is their GitHub one, and the
            // page asking GitHub for it would tell GitHub who looked at the pack.
            creators = owner?.let { listOf(CatalogueCreator(name = it, role = OWNER_ROLE, owner = true)) }.orEmpty(),
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

        /** The role an owner goes by, the word the catalogue's own teams use. */
        private const val OWNER_ROLE = "Owner"

        private const val COMMUNITY_TIER = "community"
    }
}
