package hivens.core.util

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipUtils {
    private val logger = LoggerFactory.getLogger(ZipUtils::class.java)

    fun unzip(zipFile: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        val buffer = ByteArray(8192)

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
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
    }
}
