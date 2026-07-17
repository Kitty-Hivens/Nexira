package hivens.core.update

import hivens.core.data.PackInstance

/**
 * Moves an installed pack instance to another build. The launcher implements it
 * against the mirror; abstracted so the auto-updater and UI depend on the
 * contract rather than the concrete driver, and so the policy logic can be tested
 * without touching the network or disk.
 */
interface PackUpdater {
    /** Read-only: is a different build available for [instance], and what would it change? */
    suspend fun checkForUpdate(instance: PackInstance): UpdateCheck

    /**
     * Apply an update. [targetVersion] null updates to the latest build; a specific
     * version switches or rolls back to it.
     */
    suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String? = null,
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ): UpdateOutcome
}
