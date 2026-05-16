package hivens.core.api.interfaces

import hivens.core.data.SessionData
import java.nio.file.Path

/**
 * Contract for client file download service.
 */
interface IFileDownloadService {
    /**
     * Starts the full session processing (parsing the manifest, downloading files, unpacking extra.zip).
     * * @param session User session (contains file manifest).
     * @param serverId Server ID (for logs).
     * @param targetDir Client folder.
     * @param extraCheckSum MD5 hash for extra.zip (optional).
     * @param ignoredFiles List of ignored files (optional).
     * @param messageUI Lambda for UI messages (optional).
     * @param progressUI Lambda for progress (current, total) (optional).
     */
    suspend fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?
    )
}
