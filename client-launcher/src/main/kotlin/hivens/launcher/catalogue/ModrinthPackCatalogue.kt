package hivens.launcher.catalogue

import hivens.core.api.catalogue.CatalogueCreator
import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CatalogueLink
import hivens.core.api.catalogue.CatalogueLinkKind
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthTeamMember
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin
import hivens.core.update.VersionChannel
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

    override suspend fun details(packId: String): CataloguePackDetails = coroutineScope {
        // The team is its own call and the page is whole without it, so a refusal
        // there leaves the creators empty rather than failing the page.
        val membersD = async {
            try {
                client.members(packId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("no members for modpack {}", packId, e)
                emptyList()
            }
        }
        val versionsD = async { versions(packId) }
        val p = client.resolveProject(packId)
        val versions = versionsD.await()
        CataloguePackDetails(
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
            versions = versions,
            tags = p.categories + p.additionalCategories,
            slug = p.slug,
            pageUrl = "https://modrinth.com/${p.projectType}/${p.slug}",
            projectType = p.projectType,
            downloads = p.downloads,
            followers = p.followers,
            gameVersions = p.gameVersions,
            loaders = p.loaders,
            clientSide = p.clientSide,
            serverSide = p.serverSide,
            licenseId = p.license?.id,
            licenseName = p.license?.name,
            publishedAt = p.published,
            updatedAt = p.updated,
            links = linksOf(p),
            creators = creatorsOf(membersD.await()),
            // The newest release, else the newest of anything: what the catalogue's
            // own install button reaches for.
            latestVersionId = (versions.firstOrNull { it.channel == VersionChannel.Release } ?: versions.firstOrNull())?.id,
        )
    }

    override suspend fun versions(packId: String): List<CataloguePackVersion> =
        client.listVersions(packId).map { v ->
            // A modpack version's primary file is its .mrpack.
            val archive = v.files.firstOrNull { it.primary } ?: v.files.firstOrNull()
            CataloguePackVersion(
                id = v.id,
                name = v.name,
                versionNumber = v.versionNumber,
                mcVersions = v.gameVersions,
                loaders = v.loaders,
                downloadUrl = archive?.url,
                downloadSha1 = archive?.hashes?.sha1,
                downloadSize = archive?.size ?: -1L,
                channel = VersionChannel.of(v.versionType, v.versionNumber),
                publishedAt = v.datePublished.ifBlank { null },
                changelog = v.changelog?.takeIf { it.isNotBlank() },
                downloads = v.downloads,
                // Left out: the archive is an index of downloads, a few hundred
                // kilobytes, and its size read as the size of the pack.
                sizeBytes = null,
            )
        }

    private fun linksOf(p: ModrinthProject): List<CatalogueLink> = buildList {
        p.issuesUrl?.takeIf { it.isNotBlank() }?.let { add(CatalogueLink(CatalogueLinkKind.Issues, it)) }
        p.sourceUrl?.takeIf { it.isNotBlank() }?.let { add(CatalogueLink(CatalogueLinkKind.Source, it)) }
        p.wikiUrl?.takeIf { it.isNotBlank() }?.let { add(CatalogueLink(CatalogueLinkKind.Wiki, it)) }
        p.discordUrl?.takeIf { it.isNotBlank() }?.let { add(CatalogueLink(CatalogueLinkKind.Discord, it)) }
        p.donationUrls.forEach { d ->
            d.url.takeIf { it.isNotBlank() }?.let {
                add(CatalogueLink(CatalogueLinkKind.Donate, it, label = d.platform.takeIf { n -> n.isNotBlank() }))
            }
        }
    }

    /** Owner first, then by role and name, and an invitation nobody accepted is not a credit. */
    private fun creatorsOf(members: List<ModrinthTeamMember>): List<CatalogueCreator> =
        members.filter { it.accepted }
            .map {
                CatalogueCreator(
                    name = it.user.name?.takeIf { n -> n.isNotBlank() } ?: it.user.username,
                    role = it.role,
                    avatarUrl = it.user.avatarUrl,
                    owner = it.role.equals(OWNER_ROLE, ignoreCase = true),
                )
            }
            .sortedWith(compareByDescending<CatalogueCreator> { it.owner }.thenBy { it.role }.thenBy { it.name })

    private companion object {
        const val PAGE_SIZE = 40
        const val OWNER_ROLE = "Owner"
        val log: Logger = LoggerFactory.getLogger(ModrinthPackCatalogue::class.java)
    }
}
