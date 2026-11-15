package hivens.source.modrinth

import hivens.catalogue.api.BuildDownload
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.GalleryItem
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectCreator
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthTeamMember
import hivens.core.update.VersionChannel
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * What Modrinth answers as a pack source: modpack search, the project page (body and
 * gallery), and the version list whose primary file is the installable `.mrpack`.
 * Browse lists Modrinth's packs through its project search instead
 * ([ModrinthPackSource.packType]), so [search] answers whoever lists them plainly.
 */
class ModrinthListing(private val client: ModrinthClient) : PackListing {
    private val origin: String = ModrinthPackSource.ID

    override suspend fun search(query: String, page: Int): List<CatalogueCard> =
        client.searchModpacks(query, offset = page * PAGE_SIZE, limit = PAGE_SIZE).hits.map { hit ->
            CatalogueCard(
                origin = origin,
                id = hit.projectId,
                title = hit.title.ifBlank { hit.slug },
                tagline = hit.description,
                iconUrl = hit.iconUrl,
                bannerUrl = hit.featuredGallery ?: hit.gallery.firstOrNull(),
                tags = hit.categories,
            )
        }

    override suspend fun details(packId: String): PackDetails = coroutineScope {
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
        // Full-res (raw_url) for the hero and lightbox. `url` is a 350px thumbnail
        // that upscales to mush at full-window size. Featured image is the hero,
        // else the first.
        val banner = (p.gallery.firstOrNull { it.featured } ?: p.gallery.firstOrNull())?.let { it.rawUrl ?: it.url }
        // Full-res for the lightbox, the ~350px preview for the grid, and the
        // author's own caption for both.
        val shots = p.gallery.map { g ->
            GalleryItem(
                full = g.rawUrl ?: g.url,
                thumb = g.url,
                title = g.title?.takeIf { it.isNotBlank() },
                description = g.description?.takeIf { it.isNotBlank() },
            )
        }
        PackDetails(
            origin = origin,
            id = p.id,
            title = p.title,
            tagline = p.description,
            iconUrl = p.iconUrl,
            bannerUrl = banner,
            // A banner kept apart from the shots leads the gallery.
            gallery = if (banner == null || shots.any { it.full == banner || it.thumb == banner }) shots else listOf(GalleryItem(full = banner, thumb = banner)) + shots,
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
            latestVersionId = (versions.firstOrNull { it.versionType == VersionChannel.Release.wire } ?: versions.firstOrNull())?.id,
        )
    }

    override suspend fun versions(packId: String): List<ProjectBuild> =
        client.listVersions(packId).map { v ->
            // A modpack version's primary file is its .mrpack.
            val archive = v.files.firstOrNull { it.primary } ?: v.files.firstOrNull()
            ProjectBuild(
                id = v.id,
                name = v.name,
                versionNumber = v.versionNumber,
                versionType = VersionChannel.of(v.versionType, v.versionNumber).wire,
                gameVersions = v.gameVersions,
                loaders = v.loaders,
                datePublished = v.datePublished,
                download = archive?.let { BuildDownload(url = it.url, sha1 = it.hashes.sha1, size = it.size) },
                changelog = v.changelog?.takeIf { it.isNotBlank() },
                downloads = v.downloads,
                // Left out: the archive is an index of downloads, a few hundred
                // kilobytes, and its size read as the size of the pack.
                sizeBytes = null,
            )
        }

    private fun linksOf(p: ModrinthProject): List<ProjectLink> = buildList {
        p.issuesUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Issues, it)) }
        p.sourceUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Source, it)) }
        p.wikiUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Wiki, it)) }
        p.discordUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Discord, it)) }
        p.donationUrls.forEach { d ->
            d.url.takeIf { it.isNotBlank() }?.let {
                add(ProjectLink(ProjectLinkKind.Donate, it, label = d.platform.takeIf { n -> n.isNotBlank() }))
            }
        }
    }

    /** Owner first, then by role and name, and an invitation nobody accepted is not a credit. */
    private fun creatorsOf(members: List<ModrinthTeamMember>): List<ProjectCreator> =
        members.filter { it.accepted }
            .map {
                ProjectCreator(
                    name = it.user.name?.takeIf { n -> n.isNotBlank() } ?: it.user.username,
                    role = it.role,
                    avatarUrl = it.user.avatarUrl,
                    owner = it.role.equals(OWNER_ROLE, ignoreCase = true),
                )
            }
            .sortedWith(compareByDescending<ProjectCreator> { it.owner }.thenBy { it.role }.thenBy { it.name })

    private companion object {
        const val PAGE_SIZE = 40
        const val OWNER_ROLE = "Owner"
        val log: Logger = LoggerFactory.getLogger(ModrinthListing::class.java)
    }
}
