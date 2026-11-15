package hivens.source.mirror

import hivens.core.update.PackUpdater
import hivens.launcher.PackInstaller
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.sources.PackArt
import hivens.launcher.sources.PackRepairer
import hivens.launcher.sources.SourceInstaller
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.update.PackReattacher
import hivens.launcher.update.PackUpdateService

/**
 * What the mirror does to the packs it installs: a build synced from its manifest, updates,
 * reattach and repair against the manifests it publishes per build, and the summary's
 * icon and banner as its cover.
 *
 * The build installed is the one picked, read by its own manifest: the mirror's current
 * build is never fetched in its place.
 */
class MirrorPackWork(
    private val client: SmrtPackClient,
    private val packs: PackInstaller,
    updates: PackUpdateService,
) : SourcePackWork {
    override val installer: SourceInstaller = SourceInstaller { pack, version, onReserveDir, progress ->
        val summary = client.fetchSummary(pack.id)
        val manifest = client.fetchManifestVersion(pack.id, version.id)
        packs.install(pack.id, summary, manifest, onReserveDir, progress)
    }

    override val updater: PackUpdater = updates

    override val reattacher: PackReattacher = updates

    override val repairer: PackRepairer = PackRepairer { instance, progress -> updates.verifyAndRepair(instance, progress) }

    override suspend fun art(packId: String): PackArt = client.fetchSummary(packId).let { PackArt(it.iconUrl, it.bannerUrl) }
}
