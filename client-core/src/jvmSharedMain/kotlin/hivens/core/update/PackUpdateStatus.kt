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
     *
     * [held] separates the two policies that both land here: under Ask the build
     * waits for a deliberate apply and is worth interrupting for, under Hold the
     * user asked to stay on the current build, so ambient surfaces still show the
     * newer one but nothing demands action.
     */
    data class Pending(
        val toVersion: String,
        val direction: UpdateDirection,
        val compat: CompatChange,
        val held: Boolean = false,
    ) : PackUpdateStatus

    data class Failed(val reason: String) : PackUpdateStatus
}
