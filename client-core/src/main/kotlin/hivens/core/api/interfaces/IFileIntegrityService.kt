package hivens.core.api.interfaces

import hivens.core.data.FileStatus
import java.io.IOException
import java.nio.file.Path

/**
 * Contract for file integrity checking service.
 * Responsible for calculating hashes and comparing them with reference ones.
 */
interface IFileIntegrityService {

    /**
     * Calculates the hash sum (MD5 or SHA) for the specified file.
     * Algorithm (MD5/SHA1/SHA256) is implementation defined.
     *
     * @param filePath Path to the file.
     * @return The string representation of the hash sum (hex).
     * @throws IOException in case of I/O errors when reading a file.
     */
    @Throws(IOException::class)
    fun calculateFileHash(filePath: Path): String

    /**
     * Checks the status of a single file against the expected hash.
     *
     * @param filePath Path to the file.
     * @param expectedHash Expected hash (from ClientData).
     * @return FileStatus (MISSING, MISMATCH, VALID).
     * @throws IOException in case of I/O errors.
     */
    @Throws(IOException::class)
    fun checkFile(filePath: Path, expectedHash: String): FileStatus

    /**
     * Checks the file map (obtained from ClientData) and returns
     * list of files that require updating (status MISSING or MISMATCH).
     *
     * @param basePath Client root directory (e.g. /home/user/.smarty).
     * @param filesToVerify Map (relative path -> hash) from ClientData.
     * @return Map of files to be loaded (relative path -> hash).
     */
    fun verifyIntegrity(basePath: Path, filesToVerify: Map<String, String>): Map<String, String>
}
