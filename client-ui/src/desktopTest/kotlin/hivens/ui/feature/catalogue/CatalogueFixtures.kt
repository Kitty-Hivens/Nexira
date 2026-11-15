package hivens.ui.feature.catalogue

import hivens.catalogue.api.BuildFile
import hivens.catalogue.api.Disclosure
import hivens.catalogue.api.DisclosureSource
import hivens.catalogue.api.GalleryItem
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectDetails
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthGalleryImage
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.api.dto.modrinth.ModrinthVersion

// The catalogue's own answers, read from the fixtures, as the catalogue API carries
// them. The same mapping the catalogue module makes, kept here because the module's
// own is internal to it and a render sheet starts from the fixtures.

internal fun ModrinthGameVersion.toTag(): GameVersionTag = GameVersionTag(version, versionType, major, date)

internal fun List<ModrinthGameVersion>.toTags(): List<GameVersionTag> = map { it.toTag() }

internal fun ModrinthDisclosure.toView(): Disclosure = Disclosure(
    type = type,
    note = note,
    consent = consent,
    features = features,
    dataCollected = dataCollected,
    sources = sources.map { DisclosureSource(it.label, it.link, it.note) },
)

/** Featured first, and the original where there is one, the module's rule. */
internal fun List<ModrinthGalleryImage>.toGallery(): List<GalleryItem> =
    sortedByDescending { it.featured }.map {
        GalleryItem(
            full = it.rawUrl?.takeIf { raw -> raw.isNotBlank() } ?: it.url,
            thumb = it.url,
            title = it.title,
            description = it.description,
            featured = it.featured,
        )
    }

internal fun ModrinthProject.toDetails(): ProjectDetails = ProjectDetails(
    id = id,
    slug = slug,
    title = title,
    projectType = projectType,
    description = description,
    body = body,
    categories = categories,
    additionalCategories = additionalCategories,
    iconUrl = iconUrl,
    gallery = gallery.toGallery(),
    downloads = downloads,
    followers = followers,
    loaders = loaders,
    gameVersions = gameVersions,
    clientSide = clientSide,
    serverSide = serverSide,
)

/** The project's own links, in the order the page publishes them. */
internal fun ModrinthProject.links(): List<ProjectLink> = buildList {
    issuesUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Issues, it)) }
    sourceUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Source, it)) }
    wikiUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Wiki, it)) }
    discordUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Discord, it)) }
    donationUrls.forEach { d ->
        d.url.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Donate, it, d.platform.takeIf { n -> n.isNotBlank() })) }
    }
}

internal fun ModrinthVersion.toBuild(): ProjectBuild = ProjectBuild(
    id = id,
    name = name,
    versionNumber = versionNumber,
    versionType = versionType,
    gameVersions = gameVersions,
    loaders = loaders,
    datePublished = datePublished,
    downloads = downloads,
    changelog = changelog,
    files = files.map { BuildFile(it.filename, it.size, it.primary) },
)

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
