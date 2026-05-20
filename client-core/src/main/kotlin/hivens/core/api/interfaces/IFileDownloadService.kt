package hivens.core.api.interfaces

import hivens.core.data.SessionData
import java.nio.file.Path

interface IFileDownloadService {
    /**
     * Parses the manifest, downloads files, unpacks `extra.zip`.
     *
     * [progressUI]: `(currentFile, totalFiles, downloadedBytes, totalBytes, speed)`.
     * [verifyUI]: `(verifiedCount, totalCount)` fired during the MD5
     *   integrity walk *before* downloads. Keeps the UI bar moving while
     *   hashing a 1000-file modpack -- otherwise the user sees the
     *   launcher silent for tens of seconds and assumes a hang.
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
