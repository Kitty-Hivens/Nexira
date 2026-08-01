package hivens.ui.activity

import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.core.api.interfaces.IPackRepository
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.AutoSyncService
import hivens.launcher.InstallPhase
import hivens.launcher.InstallSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bridges the services that already publish their own progress into the single
 * [ActivityRegistry], the way [hivens.ui.notifications.drivers.InstallDriver]
 * bridges them into the notification centre. Launch and game states are not here
 * because [hivens.core.launch.LaunchState] carries no target identity -- the
 * launch driver is the only place that knows which pack a launch belongs to, so
 * it reports those itself.
 *
 * Nothing is localised on this path. The registry's title is a subject name (a
 * pack, a server), and the sentence around it is composed at render time from
 * [ActivityKind] and the phase, so a locale change needs no re-report.
 */
class ActivityDriver(
    private val registry: ActivityRegistry,
    // Flows rather than the services that own them. The driver reads exactly one
    // property from each, and depending on the whole service made it impossible
    // to exercise without standing up the installer graph and the sync service --
    // which is why the first test for this file tested the registry instead.
    private val installs: StateFlow<Map<String, InstallSnapshot>>,
    private val updates: StateFlow<Map<String, PackUpdateStatus>>,
    private val sync: StateFlow<AutoSyncService.Snapshot>,
    private val repository: IPackRepository,
    private val appScope: CoroutineScope,
) {
    /**
     * The phase last handed to the registry per key, so an unchanged one is not
     * handed over again.
     *
     * Every source here keeps its settled entries: the install service holds a
     * terminal snapshot until something dismisses it, the update hub never drops
     * a key, and the sync snapshot keeps its last per-server state. Each of them
     * re-emits the WHOLE map on any change to any member, so without this gate a
     * failure the user dismissed came straight back on the next tick of an
     * unrelated job -- and a settled entry had its eviction timer restarted ten
     * times a second, which meant it never left at all.
     */
    private val reported = HashMap<String, ActivityPhase>()

    fun start() {
        appScope.launch { installs.collect { it.values.forEach(::onInstall) } }
        appScope.launch { updates.collect(::onUpdates) }
        appScope.launch { sync.collect(::onSync) }
    }

    /** Report only what changed. Returns false when the registry already has this. */
    private fun changed(key: String, phase: ActivityPhase): Boolean {
        if (reported[key] == phase) return false
        reported[key] = phase
        return true
    }

    /** Forget a key so the next genuine report is treated as new. */
    private fun forget(key: String) {
        reported.remove(key)
        registry.dismiss(key)
    }

    private fun onInstall(snapshot: InstallSnapshot) {
        val phase = when (val p = snapshot.phase) {
            is InstallPhase.Running   -> ActivityPhase.Running(p.current.toLong(), p.total.toLong(), p.filename)
            is InstallPhase.Succeeded -> ActivityPhase.Succeeded
            is InstallPhase.Failed    -> ActivityPhase.Failed(p.message)
            InstallPhase.Cancelled    -> ActivityPhase.Cancelled
        }
        val key = "install:${snapshot.key}"
        if (!changed(key, phase)) return
        registry.report(
            key     = key,
            kind    = ActivityKind.Install,
            title   = snapshot.title,
            iconUrl = snapshot.iconUrl,
            phase   = phase,
            // Cancel is real here: the install service owns the job and deletes
            // the partial directory. Actions the launcher cannot actually
            // perform stay off the list -- a control that looks live and does
            // nothing is worse than no control.
            actions = if (phase is ActivityPhase.Running) setOf(ActivityAction.Cancel) else emptySet(),
        )
    }

    private suspend fun onUpdates(statuses: Map<String, PackUpdateStatus>) {
        for ((instanceId, status) in statuses) {
            val phase = when (status) {
                is PackUpdateStatus.Updated -> ActivityPhase.Succeeded
                is PackUpdateStatus.Failed  -> ActivityPhase.Failed(status.reason)
                // Not work in flight, so nothing to narrate -- and skipping the
                // report is not enough: the status map keeps its last value, so a
                // check that settles would leave whatever was reported before it
                // on screen for the rest of the session. Drop the entry instead.
                //
                // Checking is deliberately in this group. An update check is a
                // sub-second call that usually answers from cache, and a surface
                // that flashes for every one of them is noise, not information.
                PackUpdateStatus.Checking,
                PackUpdateStatus.UpToDate,
                is PackUpdateStatus.Pending -> {
                    forget("update:$instanceId")
                    continue
                }
            }
            val key = "update:$instanceId"
            if (!changed(key, phase)) continue
            val name = repository.get(instanceId)?.displayName ?: instanceId
            registry.report(
                key   = key,
                kind  = ActivityKind.Update,
                title = name,
                phase = phase,
            )
        }
    }

    /**
     * Per-server rather than per-pass. The aggregate only knows how many failed,
     * not why, so an aggregate entry would have to invent a sentence outside the
     * string table; a per-server entry names its own subject and needs no prose.
     * Byte counts from the aggregate land on whichever server is current.
     */
    private fun onSync(snapshot: AutoSyncService.Snapshot) {
        val current = snapshot.overall as? AutoSyncService.OverallState.InProgress
        for ((serverId, state) in snapshot.perServer) {
            val phase = when (state) {
                AutoSyncService.ServerState.SYNCING ->
                    if (current != null && current.currentServer == serverId) {
                        ActivityPhase.Running(current.bytesRead, current.totalBytes)
                    } else {
                        ActivityPhase.Running(0, 0)
                    }
                AutoSyncService.ServerState.SYNCED -> ActivityPhase.Succeeded
                AutoSyncService.ServerState.FAILED -> ActivityPhase.Failed()
                // Queued and skipped are not work in flight and have no outcome
                // worth a line of chrome. Dropping the entry rather than skipping
                // the report is the point: a server that goes SYNCING -> SKIPPED
                // (two-factor with no cached manifest, a missing helper) would
                // otherwise leave its in-flight entry on a surface that never
                // evicts one by age.
                AutoSyncService.ServerState.QUEUED,
                AutoSyncService.ServerState.SKIPPED -> {
                    forget("sync:$serverId")
                    continue
                }
            }
            val key = "sync:$serverId"
            if (!changed(key, phase)) continue
            registry.report(
                key   = key,
                kind  = ActivityKind.Sync,
                title = serverId,
                phase = phase,
            )
        }
    }
}
