package hivens.core.update

/**
 * Per-instance state the background auto-updater publishes for the UI to badge.
 */
sealed interface PackUpdateStatus {
    data object Checking : PackUpdateStatus
    data object UpToDate : PackUpdateStatus
    data class Updated(val toVersion: String) : PackUpdateStatus

    /** A newer build is available but was not auto-applied (an amber change held by policy). */
    data class Pending(val toVersion: String, val compat: CompatChange) : PackUpdateStatus

    data class Failed(val reason: String) : PackUpdateStatus
}
