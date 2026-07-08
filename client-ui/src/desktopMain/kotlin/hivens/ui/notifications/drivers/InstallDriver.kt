package hivens.ui.notifications.drivers

import hivens.launcher.InstallPhase
import hivens.launcher.InstallSnapshot
import hivens.launcher.PackInstallService
import hivens.ui.i18n.AppStrings
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges [PackInstallService.installs] to the [NotificationCenter], the way
 * [LaunchDriver] bridges the launch flow. Because the install now runs on the
 * app scope, a user who kicks off an install in Browse and navigates elsewhere
 * still gets a running/finished/failed notification -- the "no signal at all"
 * the composition-scoped install used to leave behind.
 *
 * The service publishes the WHOLE install map on every tick, so a naive
 * per-emission push would re-announce install A each time install B advanced.
 * [lastPhase] gates pushes on an actual phase change per key; a Running tick
 * only pushes for the key that changed, and terminal states push exactly once.
 * NotificationCenter still coalesces a run of Progress events into one row.
 */
class InstallDriver(
    private val service: PackInstallService,
    private val notifications: NotificationCenter,
    private val appScope: CoroutineScope,
    private val stringsProvider: () -> AppStrings,
) {
    private val lastPhase = HashMap<String, InstallPhase>()

    fun start() {
        appScope.launch {
            service.installs.collect { installs ->
                installs.values.forEach { snapshot ->
                    if (lastPhase[snapshot.key] != snapshot.phase) {
                        lastPhase[snapshot.key] = snapshot.phase
                        push(snapshot)
                    }
                }
                // Forget keys the service has dropped so a later reinstall of
                // the same pack is treated as a fresh phase transition.
                lastPhase.keys.retainAll(installs.keys)
            }
        }
    }

    private fun push(snapshot: InstallSnapshot) {
        val s = stringsProvider()
        val sourceKey = "install:${snapshot.key}"
        when (val phase = snapshot.phase) {
            is InstallPhase.Running -> {
                val fraction = if (phase.total > 0) phase.current.toFloat() / phase.total else Float.NaN
                notifications.push(
                    sourceKey = sourceKey,
                    sender    = snapshot.title,
                    iconUrl   = snapshot.iconUrl,
                    severity  = Severity.Info,
                    kind      = Kind.Progress,
                    title     = s.notifInstallSyncing(snapshot.title),
                    body      = if (phase.total > 0)
                        s.browseDetailInstallProgress(phase.filename, phase.current, phase.total)
                    else s.browseDetailInstallStarting,
                    progress  = fraction,
                    actions   = listOf(
                        NotifAction("cancel", s.notifActionCancel) { service.cancel(snapshot.key) },
                    ),
                )
            }
            is InstallPhase.Succeeded -> notifications.push(
                sourceKey = sourceKey,
                sender    = snapshot.title,
                iconUrl   = snapshot.iconUrl,
                severity  = Severity.Success,
                kind      = Kind.OneShot,
                title     = s.notifInstallDone(snapshot.title),
            )
            is InstallPhase.Failed -> notifications.push(
                sourceKey = sourceKey,
                sender    = snapshot.title,
                iconUrl   = snapshot.iconUrl,
                severity  = Severity.Critical,
                kind      = Kind.Sticky,
                title     = s.notifInstallFailed(snapshot.title),
                body      = phase.message.ifBlank { null },
            )
            InstallPhase.Cancelled -> notifications.push(
                sourceKey = sourceKey,
                sender    = snapshot.title,
                iconUrl   = snapshot.iconUrl,
                severity  = Severity.Info,
                kind      = Kind.OneShot,
                title     = s.notifInstallCancelled(snapshot.title),
            )
        }
    }
}
