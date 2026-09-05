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
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * Which instance the screen was navigated to, or that there is none.
 *
 * There is no loading arm. The registry is a value that always has one, so the
 * answer exists the moment the screen is constructed; a Loading state only ever
 * described the gap between composing and the effect that started collecting, and
 * spending a frame on a spinner over a record already in memory is the whole of
 * "installed packs are empty when I open them".
 */
internal sealed interface PackResolution {
    data object NotFound : PackResolution
    data class Ready(val pack: PackInstance) : PackResolution
}

/**
 * State holder for [PackDetailScreen]: following the instance record and the
 * launch intents.
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
    var resolution by mutableStateOf(resolve(repo.observe().value))
        private set

    val pack: PackInstance? get() = (resolution as? PackResolution.Ready)?.pack

    /** `instances/<dir>` for the resolved pack, or null before it resolves. */
    val instanceDir: Path? get() = pack?.let { dataDir.resolve("instances").resolve(it.instanceDirName) }

    /**
     * Follows the record for as long as the screen is up.
     *
     * Every write re-emits the whole registry -- a rename or a toggle from the
     * settings window, a build applied on the app scope, the auto-updater at
     * startup, the playtime the launch writes back when the game exits -- so the
     * screen is one of the readers of the record rather than the holder of a copy
     * taken when it opened. The first answer is taken at construction rather than
     * from this collector, so the screen opens on the pack instead of on a spinner;
     * an instance missing from a later emission has been deleted, which is a dead
     * end and says so.
     *
     * Never returns: the effect that starts it is what bounds it.
     */
    suspend fun observe() {
        repo.observe().collect { resolution = resolve(it) }
    }

    private fun resolve(instances: List<PackInstance>): PackResolution =
        instances.firstOrNull { it.id == instanceId }
            ?.let { PackResolution.Ready(it) }
            ?: PackResolution.NotFound

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
            // Launch first, then observe: the controller answers whether it took
            // this launch, and only a launch that started has anything to narrate.
            // The state it publishes is a StateFlow, so the observer still sees the
            // Prepare it subscribes after.
            launch     = { session, pack ->
                if (controller.launchPackInstance(session, pack)) {
                    launchDriver.observe(LaunchTarget.Pack(pack))
                }
            },
            abort      = controller::abort,
            openInFileManager = { dir -> SystemActions.openFolder(dir.toString()) },
        )
    }
}
