package hivens.launcher

import hivens.core.api.interfaces.IFileIntegrityService
import hivens.core.data.FileStatus
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Реализация сервиса проверки целостности файлов.
 * Использует MD5 для вычисления хэшей.
 */
class FileIntegrityService : IFileIntegrityService {

    companion object {
        private val log = LoggerFactory.getLogger(FileIntegrityService::class.java)
        private const val HASH_ALGORITHM = "MD5"
        private const val BUFFER_SIZE = 8192
    }

    private val digestProvider = ThreadLocal.withInitial {
        try {
            MessageDigest.getInstance(HASH_ALGORITHM)
        } catch (e: NoSuchAlgorithmException) {
            log.error("Fatal: {} algorithm not supported.", HASH_ALGORITHM, e)
            throw RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    override fun calculateFileHash(filePath: Path): String {
        val digest = digestProvider.get()
        digest.reset()

        Files.newInputStream(filePath).use { inputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        return bytesToHex(digest.digest())
    }

    @Throws(IOException::class)
    override fun checkFile(filePath: Path, expectedHash: String): FileStatus {
        if (!Files.exists(filePath)) {
            log.trace("File MISSING: {}", filePath)
            return FileStatus.MISSING
        }

        val actualHash = calculateFileHash(filePath)

        if (!expectedHash.equals(actualHash, ignoreCase = true)) {
            log.warn("File MISMATCH: {} (Expected: {}, Actual: {})", filePath, expectedHash, actualHash)
            return FileStatus.MISMATCH
        }

        log.trace("File VALID: {}", filePath)
        return FileStatus.VALID
    }

    override fun verifyIntegrity(basePath: Path, filesToVerify: Map<String, String>): Map<String, String> {
        val mismatchedFiles = HashMap<String, String>()

        for ((relativePath, expectedHash) in filesToVerify) {
            val absolutePath = basePath.resolve(relativePath)

            try {
                val status = checkFile(absolutePath, expectedHash)

                if (status != FileStatus.VALID) {
                    // Файл отсутствует или поврежден, его нужно загрузить
                    mismatchedFiles[relativePath] = expectedHash
                }
            } catch (e: IOException) {
                log.error("Failed to check integrity for {}", absolutePath, e)
                // Принудительно добавляем файл в список на загрузку, если произошла ошибка I/O
                mismatchedFiles[relativePath] = expectedHash
            }
        }

        log.info("Integrity check complete. Mismatched/Missing files: {}", mismatchedFiles.size)
        return mismatchedFiles
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexString = StringBuilder(2 * bytes.size)
        for (b in bytes) {
            val hex = Integer.toHexString(0xff and b.toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }
}
