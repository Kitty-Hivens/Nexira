package hivens.core.update

/**
 * Per-instance state the background auto-updater publishes for the UI to badge.
 */
sealed interface PackUpdateStatus {
    data object Checking : PackUpdateStatus
    data object UpToDate : PackUpdateStatus
    data class Updated(val toVersion: String) : PackUpdateStatus

    /**
     * A different build is available but was not auto-applied (an amber change
     * held by policy). [direction] rides along because every ambient consumer
     * labels this state, and a mirror-side rollback of latest reaches here too:
     * announcing that as "update available" points the user at a build older
     * than the one they are running.
     */
    data class Pending(
        val toVersion: String,
        val direction: UpdateDirection,
        val compat: CompatChange,
    ) : PackUpdateStatus

    data class Failed(val reason: String) : PackUpdateStatus
}
