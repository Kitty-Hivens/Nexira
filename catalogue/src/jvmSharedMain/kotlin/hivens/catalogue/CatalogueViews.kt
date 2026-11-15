package hivens.catalogue

import hivens.catalogue.api.BuildFile
import hivens.catalogue.api.Disclosure
import hivens.catalogue.api.DisclosureSource
import hivens.catalogue.api.GalleryItem
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallRefusal
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectDetails
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.SkipReason
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthGalleryImage
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.content.ContentKind
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.ModInstaller

// The engine's records as the catalogue API carries them: plain data, named the way the
// API names it, with nothing a module could not have compiled against. What a source
// answers it maps itself. What is left here is the content a pack holds, the installs,
// and the project and build pages, which still read the engine's client.

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
