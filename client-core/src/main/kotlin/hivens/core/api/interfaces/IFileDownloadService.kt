package hivens.core.api.interfaces

import hivens.core.data.SessionData
import java.nio.file.Path

/**
 * Contract for client file download service.
 */
interface IFileDownloadService {
    /**
     * Starts the full session processing (parsing the manifest, downloading files, unpacking extra.zip).
     *
     * @param session User session (contains file manifest).
     * @param serverId Server ID (for logs).
     * @param targetDir Client folder.
     * @param extraCheckSum MD5 hash for extra.zip (optional).
     * @param ignoredFiles List of ignored files (optional).
     * @param messageUI Lambda for UI messages (optional).
     * @param progressUI Lambda for download progress
     *   `(currentFile, totalFiles, downloadedBytes, totalBytes, speed)`.
     * @param verifyUI Lambda fired during the MD5 integrity walk *before* the
     *   download phase: `(verifiedCount, totalCount)`. Useful to keep the UI's
     *   progress bar moving while the launcher hashes a 1000-file modpack --
     *   otherwise the user sees "Sync... 20%" silent for tens of seconds and
     *   assumes the launcher has hung.
     */
    suspend fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?,
        verifyUI: ((Int, Int) -> Unit)? = null,
    )
}
