package hivens.ui.logic

sealed class LaunchState {
    data object Idle : LaunchState()
    
    data class Prepare(
        val stepName: String, 
        val progress: Float // 0.0 to 1.0
    ) : LaunchState()

    data class Downloading(
        val fileName: String,
        val currentFileIdx: Int,
        val totalFiles: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedStr: String, // например "2.5 MB/s"
        val progress: Float // Общий прогресс (0.0 to 1.0)
    ) : LaunchState()

    data class GameRunning(
        val process: Process
    ) : LaunchState()

    data class Error(
        val message: String,
        val error: Throwable? = null
    ) : LaunchState()
}
