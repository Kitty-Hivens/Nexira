package hivens.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.core.data.LauncherUpdate
import org.koin.compose.getKoin
import java.nio.file.Path

/**
 * What the host the launcher runs on can do for it, asked before it is offered.
 *
 * A desktop can replace the launcher with a newer build, put a menu entry in the
 * desktop, move the data directory on the next start and restart into recovery. A
 * phone does none of those, and a screen that offers one of them there would be
 * offering something that fails. Each capability has a flag, and the screens draw
 * the control only where it is set. Every default says "not supported", so a host
 * that supplies nothing gets nothing offered.
 */
interface HostActions {
    val updatesSupported: Boolean get() = false

    /** The newer build, or null when there is none. Only asked while [updatesSupported]. */
    suspend fun checkForUpdate(): LauncherUpdate? = null

    val menuEntrySupported: Boolean get() = false

    /** Blocking. Only asked while [menuEntrySupported]. */
    fun installMenuEntry(): Result<Path> = Result.failure(UnsupportedOperationException("no menu entry on this host"))

    val dataDirMoveSupported: Boolean get() = false

    /** True when the move is scheduled for the next start. Only asked while [dataDirMoveSupported]. */
    fun scheduleDataDirMove(source: Path, target: Path): Boolean = false

    val restartSupported: Boolean get() = false

    /** The window's own title bar, drawn by the launcher instead of the system. */
    val customChromeSupported: Boolean get() = false

    /** Hiding the launcher into the tray once the game is up. */
    val hideAfterLaunchSupported: Boolean get() = false

    /** Arms the recovery marker and restarts. Only asked while [restartSupported]. */
    fun restartIntoRecovery(dataDir: Path) = Unit
}

/** A host that supplies nothing. */
object NoHostActions : HostActions

/** The host's actions, or [NoHostActions] where the host bound none. */
@Composable
fun rememberHostActions(): HostActions {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<HostActions>() ?: NoHostActions }
}
