package hivens.core.util

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipUtils {
    private val logger = LoggerFactory.getLogger(ZipUtils::class.java)

    /**
     * Unzips [zipFile] into [destDir]. Returns the relative paths of every
     * file (NOT directory) extracted, normalised to forward slashes, in
     * the order they appeared in the archive. Existing callers that
     * ignore the return value are unaffected.
     *
     * The path list is what powers `FileDownloadService`'s extra.zip
     * orphan-pruning — by snapshotting the contents of the previous
     * unpack we can diff against the new contents and remove files that
     * the upstream modpack dropped.
     */
    fun unzip(zipFile: File, destDir: File): List<String> {
        if (!destDir.exists()) destDir.mkdirs()
        val buffer = ByteArray(8192)
        val extracted = mutableListOf<String>()

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newFile = File(destDir, zipEntry.name)

                // Zip Slip protection (preventing path traversal vulnerability)
                val destDirPath = destDir.canonicalPath
                val destFilePath = newFile.canonicalPath
                if (!destFilePath.startsWith(destDirPath + File.separator)) {
                    logger.warn("Missed attempt to go outside the folder when unpacking: {}", zipEntry.name)
                    zipEntry = zis.nextEntry
                    continue
                }

                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()

                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    extracted += zipEntry.name.replace('\\', '/')
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
        return extracted
    }
}
