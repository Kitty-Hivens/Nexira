package hivens.source.modrinth

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackOrigin
import hivens.core.update.PackUpdater
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.launcher.sources.PackArt
import hivens.launcher.sources.SourceInstaller
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.update.ArchiveBuild
import hivens.launcher.update.ArchiveFeed
import hivens.launcher.update.ArchiveFile
import hivens.launcher.update.ArchivePackUpdater
import hivens.launcher.update.PackReattacher
import java.io.IOException
import java.nio.file.Path

/**
 * What Modrinth does to the packs it installs: a build's `.mrpack` installed through the
 * engine's archive installer, updates and reattach through the engine's archive updater
 * over [ModrinthArchiveFeed], and the project's icon and featured shot as its cover. No
 * repair: Modrinth publishes no per-file list of a build to measure a pack against.
 */
class ModrinthPackWork(
    private val client: ModrinthClient,
    private val mrpack: MrpackInstaller,
    archives: ArchivePackUpdater,
) : SourcePackWork {
    private val origin = PackOrigin(ModrinthPackSource.ID)

    override val installer: SourceInstaller = SourceInstaller { pack, version, onReserveDir, progress ->
        val url = version.downloadUrl
            ?: throw IOException("Modrinth version ${version.id} carries no .mrpack download URL")
        val sha1 = version.downloadSha1
            ?: throw IOException("Modrinth version ${version.id} publishes no digest for its .mrpack")
        mrpack.installFromUrl(
            url = url,
            sha1 = sha1,
            size = version.downloadSize,
            // Stamped so the update flow finds newer versions of this project later.
            source = MrpackSource(origin, pack.id, version.versionNumber, buildKey = version.id),
            iconUrl = pack.iconUrl,
            bannerUrl = pack.bannerUrl,
            onReserveDir = onReserveDir,
            progress = progress,
        )
    }

    override val updater: PackUpdater = archives

    override val reattacher: PackReattacher = archives

    override suspend fun art(packId: String): PackArt = client.resolveProject(packId).let { p ->
        PackArt(
            iconUrl = p.iconUrl,
            // Full-res (raw_url): the hero banner upscales a 350px `url` thumbnail.
            bannerUrl = (p.gallery.firstOrNull { it.featured } ?: p.gallery.firstOrNull())?.let { it.rawUrl ?: it.url },
        )
    }
}

/** Modrinth's builds of a pack as the archive updater reads them: its version list, its CDN and the project's title. */
class ModrinthArchiveFeed(private val client: ModrinthClient) : ArchiveFeed {
    override val sourceName: String = "Modrinth"

    override suspend fun builds(packId: String): List<ArchiveBuild> = client.listVersions(packId).map { it.toArchiveBuild() }

    override suspend fun download(url: String, target: Path, sha1: String) = client.downloadTo(url, target, sha1)

    override suspend fun title(packId: String): String = client.resolveProject(packId).title
}

/** A modpack version's primary file is its `.mrpack`. */
internal fun ModrinthVersion.toArchiveBuild(): ArchiveBuild = ArchiveBuild(
    id = id,
    packId = projectId,
    versionNumber = versionNumber,
    versionType = versionType,
    datePublished = datePublished,
    gameVersions = gameVersions,
    loaders = loaders,
    changelog = changelog,
    archive = (files.firstOrNull { it.primary } ?: files.firstOrNull())?.let { ArchiveFile(it.url, it.hashes.sha1) },
)
