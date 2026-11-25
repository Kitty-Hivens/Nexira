package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import hivens.accounts.api.SessionState
import hivens.core.api.interfaces.ISettingsService
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.launch.LaunchBlock
import hivens.core.launch.LaunchControlMode
import hivens.core.launch.LaunchState
import hivens.core.launch.launchBlockFor
import hivens.i18n.api.Strings
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.packs.api.PackKeys
import hivens.packs.api.PackView
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.Companion.controlMode
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.nx.PlayTone
import hivens.widget.api.rememberScopedCommand
import hivens.widget.model.Show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
     * How the control is drawn: offering its verb, waiting on something in progress,
     * or refusing. A block the player can act on, signing in, offers a verb.
     */
    val tone: PlayTone,
    /** The share of the work being waited on, or null when its size is not known. */
    val progress: Float?,
    val onClick: () -> Unit,
) {
    /** Whether a press does anything. */
    val actionable: Boolean get() = tone == PlayTone.Ready
}

/**
 * What [pack]'s launch control shows and does now, as [appState] has the launcher
 * signed in. Play and Stop fire the packs module's commands, so a control drawn where
 * that module is not brought in offers a press that does nothing.
 *
 * A null [appState] is a launcher with no accounts module, where nobody is ever
 * signed in. Play then follows the packs module's rule for nobody: it runs as the
 * offline name where one is kept, and is refused without one, with no sign-in to
 * send the person to.
 */
@Composable
fun rememberLaunchControl(
    pack: PackView,
    appState: SessionState?,
    playLabel: String? = null,
): LaunchControl = rememberLaunchControl(pack.id, pack.folder, appState, playLabel)

/**
 * The launch control of the pack [packId], whose folder under the instances folder is
 * [folder], for a surface that holds the pack's record rather than the packs module's
 * view of it, the pack page.
 */
@Composable
fun rememberLaunchControl(
    packId: String,
    folder: String,
    appState: SessionState?,
    playLabel: String? = null,
): LaunchControl {
    val session = (appState as? SessionState.SignedIn)?.session
    val s = nexiraStrings()
    val controller: LauncherController = koinInject()
    val indications: IndicationCenter = koinInject()
    val registry: InstanceWorkRegistry = koinInject()
    val settingsService: ISettingsService = koinInject()
    val navRequests: NavRequests = koinInject()
    val paths: PlatformPaths = koinInject()
    val operations: PackOperationService = koinInject()
    val contentUpdater: InstanceContentUpdater = koinInject()
    // Play and Stop are the pack commands, so a press does here what it does from
    // any other widget: Play runs as the composition's session, or offline.
    val launchPack = rememberScopedCommand(PackKeys.LaunchPack)
    val stopPack = rememberScopedCommand(PackKeys.StopPack)

    val indication by indications.launchIndication(packId).collectAsState()
    val works by registry.current.collectAsState()
    val work = works[packId]
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
    val present by produceState(true, folder, work) {
        value = withContext(Dispatchers.IO) {
            Files.isDirectory(paths.dataDir.resolve("instances").resolve(folder))
        }
    }

    val runningOperations by operations.operations.collectAsState()
    val contentRuns by contentUpdater.runs.collectAsState()

    val mode = indication.controlMode()
    val block = if (mode != LaunchControlMode.Play) {
        null
    } else {
        launchBlockFor(
            hasIdentity = session != null || !offlineName.isNullOrBlank(),
            work = work,
            instancePresent = present,
            otherLaunchActive = !launcherIdle,
            identityResolving = appState is SessionState.Resolving,
        )
    }

    val launch: () -> Unit = { launchPack?.invoke(packId) }

    // The share of whatever the control is waiting on, where its source counts. An
    // update or repair started from the pack reports files done; a content batch
    // reports its own; the auto-updater and crash recovery report nothing, and draw
    // as work of unknown size.
    val workProgress: Float? = when (work) {
        InstanceWork.Update, InstanceWork.Repair ->
            (runningOperations[packId]?.phase as? PackOperationPhase.Running)
                ?.takeIf { it.total > 0 }?.let { it.current.toFloat() / it.total }
        InstanceWork.ContentUpdate ->
            contentRuns[contentUpdater.keyOf(paths.dataDir.resolve("instances").resolve(folder))]
                ?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total }
        else -> null
    }

    return when {
        mode == LaunchControlMode.Stop -> LaunchControl(
            mode, null, s.text("packPlayExit"), NxIcon.Stop, PlayTone.Ready, null, onClick = { stopPack?.invoke(packId) },
        )
        mode == LaunchControlMode.Wait -> LaunchControl(
            mode, null,
            if (indication == LaunchIndication.Stopping) s.text("launchStopping") else s.text("packPlayWait"),
            if (indication == LaunchIndication.Stopping) NxIcon.Stop else NxIcon.PlayArrow,
            PlayTone.Waiting,
            progress = (indication as? LaunchIndication.Downloading)?.progress,
            onClick = {},
        )
        block != null -> LaunchControl(
            mode = mode,
            block = block,
            label = block.label(s),
            icon = block.icon(),
            tone = when (block) {
                // Signing in is the way out, where there is a way to sign in.
                LaunchBlock.NoIdentity -> if (appState == null) PlayTone.Unavailable else PlayTone.Ready
                is LaunchBlock.Busy, LaunchBlock.ResolvingIdentity -> PlayTone.Waiting
                LaunchBlock.Missing -> PlayTone.Problem
                LaunchBlock.OtherGameRunning -> PlayTone.Unavailable
            },
            progress = if (block is LaunchBlock.Busy) workProgress else null,
            onClick = if (block == LaunchBlock.NoIdentity && appState != null) {
                { navRequests.open(Show(LauncherConfigs.Profile)) }
            } else {
                {}
            },
        )
        else -> LaunchControl(
            mode = mode,
            block = null,
            label = if (session == null) s.text("loginPlayOffline") else playLabel ?: s.text("packDetailPlay"),
            icon = NxIcon.PlayArrow,
            tone = PlayTone.Ready,
            progress = null,
            onClick = launch,
        )
    }
}

private fun LaunchBlock.label(s: Strings): String = when (this) {
    is LaunchBlock.Busy -> work.label(s)
    LaunchBlock.Missing -> s.text("launchBlockMissing")
    LaunchBlock.OtherGameRunning -> s.text("launchBlockOtherRunning")
    LaunchBlock.ResolvingIdentity -> s.text("launchBlockSigningIn")
    LaunchBlock.NoIdentity -> s.text("packDetailPlayLoginRequired")
}

/** What the work in progress on a pack is called, wherever it is the reason for waiting. */
internal fun InstanceWork.label(s: Strings): String = when (this) {
    InstanceWork.Update -> s.text("launchBlockUpdating")
    InstanceWork.Repair -> s.text("launchBlockRepairing")
    InstanceWork.Recovery -> s.text("launchBlockRecovering")
    InstanceWork.ContentUpdate -> s.text("launchBlockUpdatingContent")
    InstanceWork.ContentInstall -> s.text("launchBlockInstallingContent")
    InstanceWork.Delete -> s.text("launchBlockDeleting")
}

private fun LaunchBlock.icon(): IconKey = when (this) {
    is LaunchBlock.Busy -> when (work) {
        InstanceWork.Update -> NxIcon.Update
        InstanceWork.Repair -> NxIcon.Build
        InstanceWork.Recovery -> NxIcon.History
        InstanceWork.ContentUpdate -> NxIcon.Sync
        InstanceWork.ContentInstall -> NxIcon.Download
        InstanceWork.Delete -> NxIcon.Delete
    }
    LaunchBlock.Missing -> NxIcon.Warning
    LaunchBlock.OtherGameRunning -> NxIcon.HourglassEmpty
    LaunchBlock.ResolvingIdentity, LaunchBlock.NoIdentity -> NxIcon.Person
}
