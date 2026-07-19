package hivens.core.update

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-instance pack-update state, shared between the background auto-updater
 * (its passes write here) and manual check/apply flows ([report]), so ambient
 * consumers -- card badges, the hero chip, the notification driver -- reflect
 * one coherent reality regardless of who moved the instance.
 */
interface PackUpdateStatusHub {
    val statuses: StateFlow<Map<String, PackUpdateStatus>>

    /** Publish the outcome of a manual check/apply for [id] (e.g. clear a stale Pending). */
    fun report(id: String, status: PackUpdateStatus)
}
