package hivens.ui.platform

import hivens.core.data.LauncherUpdate
import hivens.launcher.platform.AppRelauncher
import hivens.launcher.platform.DataDirMover
import hivens.ui.bootstrap.RecoveryEntry
import hivens.update.DesktopIntegration
import hivens.update.UpdateService
import java.nio.file.Path
import kotlin.system.exitProcess

/** What a desktop can do: the launcher replaces itself, adds a menu entry, moves its data and restarts. */
class DesktopHostActions(
    private val updateService: UpdateService,
    private val desktop: DesktopIntegration,
) : HostActions {

    override val updatesSupported: Boolean = true

    override suspend fun checkForUpdate(): LauncherUpdate? = updateService.checkForUpdate()

    override val menuEntrySupported: Boolean get() = desktop.isSupported()

    override fun installMenuEntry(): Result<Path> = desktop.installEntry()

    override val dataDirMoveSupported: Boolean = true

    override fun scheduleDataDirMove(source: Path, target: Path): Boolean = DataDirMover.schedule(source = source, target = target)

    override val restartSupported: Boolean = true
    override val customChromeSupported: Boolean = true
    override val hideAfterLaunchSupported: Boolean = true

    override fun restartIntoRecovery(dataDir: Path) {
        RecoveryEntry.requestOnNextBoot(dataDir)
        if (AppRelauncher.relaunch()) exitProcess(0)
    }
}
