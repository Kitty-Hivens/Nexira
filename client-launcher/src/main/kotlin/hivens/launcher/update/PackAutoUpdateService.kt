package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.AmberUpdatePolicy
import hivens.core.data.PackOrigin
import hivens.core.data.SettingsData
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory

/**
 * Background auto-updater over installed mirror instances, a sibling of the
 * SC-server-side AutoSyncService. On a trigger (startup, or a manual "check
 * all") it visits each mirror instance that follows latest and updates it per
 * policy: a green (safe re-sync) update applies automatically; an amber
 * (structural MC / loader) update applies only under
 * [AmberUpdatePolicy.SnapshotThenApply], otherwise it is left pending. The two
 * non-applying policies differ in what the pending state asks of the user:
 * [AmberUpdatePolicy.Ask] expects a deliberate apply, [AmberUpdatePolicy.Hold]
 * expects nothing, and the status carries that so ambient surfaces can show the
 * build without demanding action.
 *
 * Per-instance progress is published on [statuses] so the UI can badge a card.
 * A per-instance failure is recorded and never aborts the rest of the pass.
 */
class PackAutoUpdateService(
    private val repository: IPackRepository,
    private val updater: PackUpdater,
    private val settingsProvider: () -> SettingsData,
) : PackUpdateStatusHub {
    private val log = LoggerFactory.getLogger(PackAutoUpdateService::class.java)
    private val state = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
    override val statuses: StateFlow<Map<String, PackUpdateStatus>> = state.asStateFlow()

    override fun report(id: String, status: PackUpdateStatus) = setStatus(id, status)

    /** Run one pass over eligible instances. No-op when auto-update is off in settings. */
    suspend fun runOnce() {
        val settings = settingsProvider()
        if (!settings.autoUpdatePacks) return
        for (instance in repository.list()) {
            if (instance.packRef.origin != PackOrigin.Mirror || !instance.followLatest) continue
            setStatus(instance.id, PackUpdateStatus.Checking)
            try {
                when (val check = updater.checkForUpdate(instance)) {
                    UpdateCheck.UpToDate -> setStatus(instance.id, PackUpdateStatus.UpToDate)
                    is UpdateCheck.Available -> applyOrHold(instance.id, check, settings.amberUpdatePolicy)
                }
            } catch (e: Exception) {
                log.warn("auto-update: failed for {}", instance.id, e)
                setStatus(instance.id, PackUpdateStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    private suspend fun applyOrHold(id: String, check: UpdateCheck.Available, amber: AmberUpdatePolicy) {
        val autoApply = check.compat.isSafe || amber == AmberUpdatePolicy.SnapshotThenApply
        if (!autoApply) {
            setStatus(
                id,
                PackUpdateStatus.Pending(
                    check.toVersion,
                    check.direction,
                    check.compat,
                    held = amber == AmberUpdatePolicy.Hold,
                ),
            )
            return
        }
        val instance = repository.get(id) ?: return
        val status = when (updater.applyUpdate(instance)) {
            is UpdateOutcome.Applied -> PackUpdateStatus.Updated(check.toVersion)
            UpdateOutcome.AlreadyCurrent -> PackUpdateStatus.UpToDate
        }
        setStatus(id, status)
    }

    private fun setStatus(id: String, status: PackUpdateStatus) {
        state.update { it + (id to status) }
    }
}
