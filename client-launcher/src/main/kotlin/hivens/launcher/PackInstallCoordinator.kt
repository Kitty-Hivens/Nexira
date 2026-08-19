package hivens.launcher

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.launcher.smrt.SmrtPackClient
import java.io.IOException
import java.nio.file.Path

/**
 * Single entry point for installing a catalogue pack: dispatches by
 * [CataloguePack.origin] onto the source-specific installer. Mirror packs sync
 * from the manifest ([PackInstaller]); Modrinth packs download the version's
 * `.mrpack` and install it ([MrpackInstaller]), stamping the Modrinth origin so
 * the update flow can find newer versions later. The Browse UI calls this with
 * a source-neutral (pack, version) pair and never touches a concrete installer.
 */
class PackInstallCoordinator(
    private val mirrorInstaller: PackInstaller,
    private val mrpackInstaller: MrpackInstaller,
    private val mirrorClient: SmrtPackClient,
) {
    suspend fun install(
        pack: CataloguePack,
        version: CataloguePackVersion,
        // Invoked with the instance directory each installer reserves before it
        // writes, so a caller cancelling the install can delete exactly the
        // partial dir it left behind (see [PackInstallService]).
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = when (pack.origin) {
        PackOrigin.Mirror -> {
            val summary = mirrorClient.fetchSummary(pack.id)
            val manifest = mirrorClient.fetchManifestVersion(pack.id, version.id)
            mirrorInstaller.install(pack.id, summary, manifest, onReserveDir, progress)
        }
        PackOrigin.Modrinth -> {
            val url = version.downloadUrl
                ?: throw IOException("Modrinth version ${version.id} carries no .mrpack download URL")
            mrpackInstaller.installFromUrl(
                url = url,
                source = MrpackSource(PackOrigin.Modrinth, pack.id, version.versionNumber, buildKey = version.id),
                iconUrl = pack.iconUrl,
                bannerUrl = pack.bannerUrl,
                onReserveDir = onReserveDir,
                progress = progress,
            )
        }
        else -> throw IllegalArgumentException("install from origin ${pack.origin} is not supported")
    }
}
