package hivens.catalogue

import hivens.catalogue.api.BuildFile
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import hivens.catalogue.api.Disclosure
import hivens.catalogue.api.DisclosureSource
import hivens.catalogue.api.GalleryItem
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallRefusal
import hivens.catalogue.api.LoaderTag
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectCreator
import hivens.catalogue.api.ProjectDetails
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SkipReason
import hivens.catalogue.api.FilterField
import hivens.core.api.catalogue.CatalogueLinkKind
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthGalleryImage
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackOrigin
import hivens.launcher.content.ContentKind
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.FilterField as EngineField
import hivens.launcher.modrinth.SearchFilter as EngineFilter

// The engine's records as the catalogue API carries them: plain data, named the way the
// API names it, with nothing a module could not have compiled against.

/** The source's name as the packs and catalogue APIs give it. */
internal val PackOrigin.apiName: String get() = name.lowercase()

/** The source a name of [PackOrigin.apiName]'s stands for, or null for one this build does not know. */
internal fun originNamed(name: String): PackOrigin? = PackOrigin.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

internal fun ContentKind.toProjectKind(): ProjectKind = when (this) {
    ContentKind.Mod -> ProjectKind.Mod
    ContentKind.ResourcePack -> ProjectKind.ResourcePack
    ContentKind.ShaderPack -> ProjectKind.ShaderPack
}

internal fun ProjectKind.toContentKind(): ContentKind = when (this) {
    ProjectKind.Mod -> ContentKind.Mod
    ProjectKind.ResourcePack -> ContentKind.ResourcePack
    ProjectKind.ShaderPack -> ContentKind.ShaderPack
}

internal fun FilterField.toEngine(): EngineField = when (this) {
    FilterField.GameVersion -> EngineField.GameVersion
    FilterField.Loader -> EngineField.Loader
    FilterField.Category -> EngineField.Category
    FilterField.Resolution -> EngineField.Resolution
    FilterField.Environment -> EngineField.Environment
    FilterField.OpenSource -> EngineField.OpenSource
    FilterField.Disclosure -> EngineField.Disclosure
    FilterField.Project -> EngineField.Project
}

internal fun SearchFilter.toEngine(): EngineFilter = EngineFilter(field.toEngine(), value, excluded)

internal fun CataloguePack.toCard(): CatalogueCard = CatalogueCard(
    origin = origin.apiName,
    id = id,
    title = title,
    tagline = tagline,
    iconUrl = iconUrl,
    bannerUrl = bannerUrl,
    tags = tags,
    mcVersion = mcVersion,
    community = community,
    author = author,
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

internal fun ModrinthGameVersion.toTag(): GameVersionTag = GameVersionTag(version, versionType, major, date)

internal fun ModrinthCategoryTag.toTag(): CategoryTag = CategoryTag(name, projectType, header, icon)

internal fun ModrinthLoaderTag.toTag(): LoaderTag = LoaderTag(name, supportedProjectTypes, icon)

internal fun catalogueTags(versions: List<ModrinthGameVersion>, categories: List<ModrinthCategoryTag>, loaders: List<ModrinthLoaderTag>) =
    CatalogueTags(versions.map { it.toTag() }, categories.map { it.toTag() }, loaders.map { it.toTag() })

internal fun ModrinthDisclosure.toView(): Disclosure = Disclosure(
    type = type,
    note = note,
    consent = consent,
    features = features,
    dataCollected = dataCollected,
    sources = sources.map { DisclosureSource(it.label, it.link, it.note) },
)

/**
 * The project's shots, featured first. The thumbnail upscales to mush at full size, so
 * the full picture is the original where there is one.
 */
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

internal fun CataloguePackVersion.toBuild(): ProjectBuild = ProjectBuild(
    id = id,
    name = name,
    versionNumber = versionNumber,
    versionType = channel.name.lowercase(),
    gameVersions = mcVersions,
    loaders = loaders,
    datePublished = publishedAt.orEmpty(),
    downloads = downloads,
    changelog = changelog,
    modsCount = modsCount,
    sizeBytes = sizeBytes,
)

internal fun CatalogueLinkKind.toView(): ProjectLinkKind = when (this) {
    CatalogueLinkKind.Issues -> ProjectLinkKind.Issues
    CatalogueLinkKind.Source -> ProjectLinkKind.Source
    CatalogueLinkKind.Wiki -> ProjectLinkKind.Wiki
    CatalogueLinkKind.Discord -> ProjectLinkKind.Discord
    CatalogueLinkKind.Donate -> ProjectLinkKind.Donate
}

/**
 * The pack's shots, its banner first where the source keeps it apart from them: a
 * picture a curator chose to lead with belongs at the head of the gallery.
 */
internal fun CataloguePackDetails.galleryWithBanner(): List<GalleryItem> {
    val shots = gallery.map { GalleryItem(full = it.full, thumb = it.thumb, title = it.title, description = it.description) }
    val banner = bannerUrl ?: return shots
    return if (gallery.any { it.full == banner || it.thumb == banner }) shots else listOf(GalleryItem(full = banner, thumb = banner)) + shots
}

internal fun CataloguePackDetails.toDetails(): PackDetails = PackDetails(
    origin = origin.apiName,
    id = id,
    title = title,
    tagline = tagline,
    iconUrl = iconUrl,
    bannerUrl = bannerUrl,
    gallery = galleryWithBanner(),
    bodyMarkdown = bodyMarkdown,
    versions = versions.map { it.toBuild() },
    tags = tags,
    runtimeLabel = runtimeLabel,
    slug = slug,
    pageUrl = pageUrl,
    projectType = projectType,
    downloads = downloads,
    followers = followers,
    gameVersions = gameVersions,
    loaders = loaders,
    clientSide = clientSide,
    serverSide = serverSide,
    licenseId = licenseId,
    licenseName = licenseName,
    publishedAt = publishedAt,
    updatedAt = updatedAt,
    links = links.map { ProjectLink(it.kind.toView(), it.url, it.label) },
    creators = creators.map { ProjectCreator(it.name, it.role, it.avatarUrl, it.owner) },
    latestVersionId = latestVersionId,
    signIn = auth?.providerKeys.orEmpty(),
)

internal fun ContentInstaller.Refusal.toView(): InstallRefusal = when (this) {
    ContentInstaller.Refusal.GameRunning -> InstallRefusal.GameRunning
    is ContentInstaller.Refusal.Busy -> InstallRefusal.Busy
}

internal fun ContentInstaller.Skip.toReason(): SkipReason = when (this) {
    is ContentInstaller.Skip.Present -> SkipReason.Present
    is ContentInstaller.Skip.AlreadyInstalled -> SkipReason.AlreadyInstalled
    is ContentInstaller.Skip.NoBuild -> SkipReason.NoBuild
    is ContentInstaller.Skip.LookupFailed -> SkipReason.LookupFailed
    is ContentInstaller.Skip.NotPlaceable -> SkipReason.NotPlaceable
    is ContentInstaller.Skip.NotAllowed -> SkipReason.NotAllowed
    is ContentInstaller.Skip.NameTaken -> SkipReason.NameTaken
    is ContentInstaller.Skip.Failed -> SkipReason.Failed
    is ContentInstaller.Skip.NotAttempted -> SkipReason.NotAttempted
    is ContentInstaller.Skip.TooDeep -> SkipReason.TooDeep
    is ContentInstaller.Skip.PackOwned -> SkipReason.PackOwned
    is ContentInstaller.Skip.SwitchedOff -> SkipReason.SwitchedOff
}

/** The problem an [outcome] reports, or null when what was asked for landed. */
internal fun problemOf(outcome: ModInstaller.Outcome): InstallProblem? {
    if (outcome.ok) return null
    outcome.refusal?.let { return InstallProblem.Refused(it.toView()) }
    return outcome.headLeftOut?.let { InstallProblem.LeftOut(it.toReason()) } ?: InstallProblem.NotLanded
}
