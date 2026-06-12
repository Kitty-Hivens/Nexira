package hivens.core.launch

/**
 * Domain state machine for a launch attempt. Lives in `client-core` so the
 * launch SPI and every frontend (the Compose UI today, a headless/TUI frontend
 * tomorrow) share one Compose-free contract.
 *
 * State carries **no localized strings**. Stage labels and error messages are
 * semantic codes ([PrepareStage], [LaunchError]); the UI resolves them against
 * its string table at render time. Download progress exposes only raw byte
 * counters -- the UI divides for the fraction and formats the speed locally.
 *
 * The running process is held behind [LaunchHandle], not a raw `java.lang.Process`,
 * so the JVM process type stays internal to the launcher implementation.
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
        val handle: LaunchHandle,
    ) : LaunchState()

    data class Error(
        val reason: LaunchError,
        /** Original throwable when available (catch-all path). UI does not render this directly. */
        val cause: Throwable? = null,
    ) : LaunchState()
}

/** Coarse-grained phases of the pre-launch pipeline, used by [LaunchState.Prepare]. */
enum class PrepareStage { INIT, AUTH, SYNC, JVM, LAUNCH }
