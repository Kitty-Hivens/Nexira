package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import hivens.auth.OfflineAuthProvider
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.launch.LaunchBlock
import hivens.core.launch.LaunchControlMode
import hivens.core.launch.LaunchState
import hivens.core.launch.launchBlockFor
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.Companion.controlMode
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Files

/**
 * What one pack's launch control shows and does right now.
 *
 * Every surface that offers Play reads this rather than deciding for itself. They
 * used to decide for themselves, and each drifted in its own direction: the pack
 * page went grey without a word when nobody was signed in, the home tile said
 * "can't play yet" for every reason at once, and none of them knew that an update
 * was rewriting the files underneath.
 *
 * [label] and [icon] are the control's own words for the state. A surface with a
 * label of its own (a widget prop, a shorter home wording) passes it in, and it is
 * used only for the plain Play state, since every other state is a fact the
 * surface has no business renaming.
 */
@Immutable
data class LaunchControl(
    val mode: LaunchControlMode,
    /** Why Play is not offered, when it is not. Null in every other state. */
    val block: LaunchBlock?,
    val label: String,
    val icon: IconKey,
    /**
     * False when the control refuses: nothing will change by waiting, so it is drawn
     * as unavailable. A block the player can act on, signing in, stays enabled.
     */
    val enabled: Boolean,
    /**
     * Something is in progress and the control is waiting on it rather than refusing:
     * a launch preparing, or work on the files. Drawn as a wait, and not pressable.
     */
    val busy: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun rememberLaunchControl(
    pack: PackInstance,
    session: SessionData?,
    playLabel: String? = null,
): LaunchControl {
    val s = LocalStrings.current
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    val indications: IndicationCenter = koinInject()
    val registry: InstanceWorkRegistry = koinInject()
    val settingsService: ISettingsService = koinInject()
    val offlineProvider: OfflineAuthProvider = koinInject()
    val navRequests: NavRequests = koinInject()
    val paths: PlatformPaths = koinInject()
    val scope = rememberCoroutineScope()

    val indication by indications.launchIndication(pack.id).collectAsState()
    val works by registry.current.collectAsState()
    val work = works[pack.id]
    // Collapsed to the one question asked of it: Downloading republishes per
    // progress callback, and no control here repaints at frame rate for another
    // pack's download.
    val launcherIdle by remember(controller) {
        controller.state
            .map { it is LaunchState.Idle || it is LaunchState.Error }
            .distinctUntilChanged()
    }.collectAsState(initial = true)

    // Only consulted while signed out: the name an offline launch would run as.
    val offlineName by produceState<String?>(null, session == null) {
        if (session == null) value = withContext(Dispatchers.IO) { settingsService.getSettings().offlinePlayerName }
    }
    // Re-asked when the record changes and when work on it ends, which are the two
    // moments the folder can have appeared or gone.
    val present by produceState(true, pack.instanceDirName, work) {
        value = withContext(Dispatchers.IO) {
            Files.isDirectory(paths.dataDir.resolve("instances").resolve(pack.instanceDirName))
        }
    }

    val mode = indication.controlMode()
    val block = if (mode != LaunchControlMode.Play) {
        null
    } else {
        launchBlockFor(
            hasIdentity = session != null || !offlineName.isNullOrBlank(),
            work = work,
            instancePresent = present,
            otherLaunchActive = !launcherIdle,
        )
    }

    val launch: () -> Unit = launch@{
        if (session != null) {
            if (controller.launchPackInstance(session, pack)) launchDriver.observe(LaunchTarget(pack))
            return@launch
        }
        // Re-read at the click rather than launched under the name the control was
        // drawn with: what decides the label can lag by a frame, what the game runs
        // as cannot.
        scope.launch {
            val name = withContext(Dispatchers.IO) { settingsService.getSettings().offlinePlayerName }
            if (name.isNullOrBlank()) return@launch
            val offline = withContext(Dispatchers.IO) { offlineProvider.login(name, "", "") }
            if (controller.launchPackInstance(offline, pack)) launchDriver.observe(LaunchTarget(pack))
        }
    }

    return when {
        mode == LaunchControlMode.Stop -> LaunchControl(
            mode, null, s.packPlayExit, NxIcon.Stop, enabled = true, busy = false, onClick = controller::abort,
        )
        mode == LaunchControlMode.Wait -> LaunchControl(
            mode, null, s.packPlayWait, NxIcon.PlayArrow, enabled = true, busy = true, onClick = {},
        )
        block != null -> LaunchControl(
            mode = mode,
            block = block,
            label = block.label(s),
            icon = block.icon(),
            enabled = block == LaunchBlock.NoIdentity || block is LaunchBlock.Busy,
            busy = block is LaunchBlock.Busy,
            onClick = if (block == LaunchBlock.NoIdentity) {
                { navRequests.open(Screen.Profile) }
            } else {
                {}
            },
        )
        else -> LaunchControl(
            mode = mode,
            block = null,
            label = if (session == null) s.loginPlayOffline else playLabel ?: s.packDetailPlay,
            icon = NxIcon.PlayArrow,
            enabled = true,
            busy = false,
            onClick = launch,
        )
    }
}

private fun LaunchBlock.label(s: AppStrings): String = when (this) {
    is LaunchBlock.Busy -> when (work) {
        InstanceWork.Update -> s.launchBlockUpdating
        InstanceWork.Repair -> s.launchBlockRepairing
        InstanceWork.Recovery -> s.launchBlockRecovering
        InstanceWork.ContentUpdate -> s.launchBlockUpdatingContent
    }
    LaunchBlock.Missing -> s.launchBlockMissing
    LaunchBlock.OtherGameRunning -> s.launchBlockOtherRunning
    LaunchBlock.NoIdentity -> s.packDetailPlayLoginRequired
}

private fun LaunchBlock.icon(): IconKey = when (this) {
    is LaunchBlock.Busy -> when (work) {
        InstanceWork.Update -> NxIcon.Update
        InstanceWork.Repair -> NxIcon.Build
        InstanceWork.Recovery -> NxIcon.History
        InstanceWork.ContentUpdate -> NxIcon.Sync
    }
    LaunchBlock.Missing -> NxIcon.Warning
    LaunchBlock.OtherGameRunning -> NxIcon.HourglassEmpty
    LaunchBlock.NoIdentity -> NxIcon.Person
}
