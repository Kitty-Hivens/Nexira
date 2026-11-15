package hivens.launcher

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackInstance
import hivens.launcher.sources.SourceCannotException
import hivens.launcher.sources.SourceMissingException
import hivens.launcher.sources.SourceOperation
import hivens.launcher.sources.SourceStatus
import hivens.launcher.sources.SourceWorks
import java.nio.file.Path

/**
 * Single entry point for installing a catalogue pack: hands the (pack, version) pair to
 * the installer of the source [CataloguePack.origin] names, found through [sources]. Each
 * source installs its builds its own way and stamps its own origin on the record, so the
 * update flow can find newer builds later. The Browse UI calls this with a source-neutral
 * pair and never touches a concrete installer.
 *
 * A source whose module is not loaded is refused with [SourceMissingException], one that
 * is loaded and brings no installer with [SourceCannotException], and a pack of the core's
 * own as not supported. None of them is handed to another source.
 */
class PackInstallCoordinator(private val sources: SourceWorks) {
    suspend fun install(
        pack: CataloguePack,
        version: CataloguePackVersion,
        // Invoked with the instance directory each installer reserves before it
        // writes, so a caller cancelling the install can delete exactly the
        // partial dir it left behind (see [PackInstallService]).
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = when (val status = sources.status(pack.origin)) {
        is SourceStatus.Loaded -> status.work.installer?.install(pack, version, onReserveDir, progress)
            ?: throw SourceCannotException(pack.origin, SourceOperation.Install)
        is SourceStatus.Missing -> throw SourceMissingException(status.origin)
        SourceStatus.None -> throw IllegalArgumentException("install from origin ${pack.origin} is not supported")
    }
}
