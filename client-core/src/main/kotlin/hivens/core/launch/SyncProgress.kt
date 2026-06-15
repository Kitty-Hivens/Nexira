package hivens.core.launch

/**
 * File-sync progress tick from `IFileDownloadService`. Carries raw counters
 * only -- [bytesPerSec] is a plain rate the UI formats in its own locale, so no
 * pre-formatted string crosses the SPI (and no regex parse round-trips it back).
 */
data class SyncProgress(
    val currentFileIdx: Int,
    val totalFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Long,
)
