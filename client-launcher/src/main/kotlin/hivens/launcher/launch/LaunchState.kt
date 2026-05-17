package hivens.launcher.launch

/**
 * Domain state machine for a launch attempt. Lives in `client-launcher`
 * (not `client-ui`) so the orchestrator [LauncherController] can sit on
 * the right side of the module layering -- previously the controller
 * imported `client-ui` strings to produce localized state text, which
 * inverted the dependency direction.
 *
 * State carries **no localized strings**. Stage labels and error messages
 * are semantic codes ([PrepareStage], [LaunchError]); the UI side
 * (`LaunchControlPanel`) resolves them against `AppStrings` at render
 * time. Same goes for download progress -- only raw byte counters are
 * exposed, the UI divides for the progress fraction and applies any
 * cosmetic effects (April Fools regression, etc.) locally.
 */
sealed class LaunchState {
    data object Idle : LaunchState()

    data class Prepare(
        val stage: PrepareStage,
        /** Coarse progress 0.0..1.0 -- before the download phase starts. */
        val progress: Float,
    ) : LaunchState()

    data class Downloading(
        val currentFileIdx: Int,
        val totalFiles: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        /** Raw transfer speed; UI formats. */
        val speedBytesPerSec: Long,
    ) : LaunchState()

    data class GameRunning(
        val process: Process,
    ) : LaunchState()

    data class Error(
        val reason: LaunchError,
        /** Original throwable when available (catch-all path). UI does not render this directly. */
        val cause: Throwable? = null,
    ) : LaunchState()
}

/** Coarse-grained phases of the pre-launch pipeline, used by [LaunchState.Prepare]. */
enum class PrepareStage { INIT, AUTH, SYNC, JVM, LAUNCH }
