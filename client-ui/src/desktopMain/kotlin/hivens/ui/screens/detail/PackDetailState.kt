package hivens.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.flow.firstOrNull
import org.koin.compose.koinInject
import java.nio.file.Path

/** Where the screen is in resolving the instance it was navigated to. */
internal sealed interface PackResolution {
    data object Loading : PackResolution
    data object NotFound : PackResolution
    data class Ready(val pack: PackInstance) : PackResolution
}

/**
 * State holder for [PackDetailScreen]: resolving the instance, re-reading it
 * when a background operation rewrites the record, and the launch intents.
 *
 * The screen used to reach the launcher directly -- repository, controller,
 * launch driver -- and run the launch in click lambdas, twice over, since the
 * hero button and the puppet hook each spelled out the same two calls. A
 * composable that owns IO also cannot be tested without a composition, so the
 * resolve path had no coverage at all.
 *
 * Launching arrives as [launch] / [abort] lambdas rather than the controller
 * itself. The screen no longer names a launcher type, and the holder stays
 * constructible in a test without one.
 */
@Stable
internal class PackDetailState(
    private val instanceId: String,
    private val repo: IPackRepository,
    private val dataDir: Path,
    private val launch: (SessionData, PackInstance) -> Unit,
    private val abort: () -> Unit,
    private val openInFileManager: (Path) -> Unit,
) {
    var resolution by mutableStateOf<PackResolution>(PackResolution.Loading)
        private set

    val pack: PackInstance? get() = (resolution as? PackResolution.Ready)?.pack

    /** `instances/<dir>` for the resolved pack, or null before it resolves. */
    val instanceDir: Path? get() = pack?.let { dataDir.resolve("instances").resolve(it.instanceDirName) }

    /**
     * First read: the observed list if it has emitted, else a direct get. Both
     * are tried because navigation can land here before the repository's first
     * emission, and a miss on the flow is not a missing pack.
     */
    suspend fun resolve() {
        val found = repo.observe().firstOrNull()?.firstOrNull { it.id == instanceId }
            ?: repo.get(instanceId)
        resolution = if (found == null) PackResolution.NotFound else PackResolution.Ready(found)
    }

    /**
     * Re-read after a background operation finished.
     *
     * An update or a repair rewrites the record from the app scope and outlives
     * the settings window that started it, so the screen holding the snapshot is
     * the one that has to refresh -- including after a failure, whose rollback
     * writes the record too. A read that comes back empty keeps the current
     * snapshot: a transient miss must not turn the screen into a not-found.
     */
    suspend fun refresh() {
        val found = repo.get(instanceId) ?: return
        resolution = PackResolution.Ready(found)
    }

    /** Adopt an instance the settings window just rewrote, without a re-read. */
    fun adopt(instance: PackInstance) {
        resolution = PackResolution.Ready(instance)
    }

    fun play(session: SessionData) {
        val target = pack ?: return
        launch(session, target)
    }

    fun abortLaunch() = abort()

    fun openFolder() {
        instanceDir?.let(openInFileManager)
    }
}

@Composable
internal fun rememberPackDetailState(instanceId: String): PackDetailState {
    val repo: IPackRepository = koinInject()
    val paths: PlatformPaths = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    return remember(instanceId, repo, paths, controller, launchDriver) {
        PackDetailState(
            instanceId = instanceId,
            repo       = repo,
            dataDir    = paths.dataDir,
            // Observer first, then launch: the first-non-Idle await has to be
            // subscribed before Prepare fires.
            launch     = { session, pack ->
                launchDriver.observe(LaunchTarget.Pack(pack))
                controller.launchPackInstance(session, pack)
            },
            abort      = controller::abort,
            openInFileManager = { dir -> SystemActions.openFolder(dir.toString()) },
        )
    }
}
