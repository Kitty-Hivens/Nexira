package hivens.core.util

import org.apache.commons.compress.archivers.zip.ZipFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

object ZipUtils {
    private val logger = LoggerFactory.getLogger(ZipUtils::class.java)

    /**
     * Unzips [zipFile] into [destDir]. Returns the relative paths of
     * every file (forward slashes), in archive order. Callers that
     * ignore the return value are unaffected; the list powers extra.zip
     * orphan-pruning in `FileDownloadService` -- snapshot the previous
     * unpack, diff the next, remove what the upstream modpack dropped.
     *
     * Security: plain Zip Slip (`startsWith(destDir)`) catches `../`
     * traversal in entry names but not symbolic-link entries. A symlink
     * entry named `safe.txt` whose payload points to `~/.ssh/id_rsa`
     * would normalize to a path INSIDE [destDir], pass the check, then
     * cause attacker-controlled bytes to land on the linked target on
     * the next extraction touching that name. We use [ZipFile]
     * (random-access central-directory reader) rather than the
     * streaming variant: only the central directory carries the
     * external-attributes field that holds the unix file-type bits
     * needed to detect and refuse the symlink.
     */
    fun unzip(zipFile: File, destDir: File): List<String> {
        if (!destDir.exists()) destDir.mkdirs()
        val buffer = ByteArray(8192)
        val extracted = mutableListOf<String>()
        val destDirPath = destDir.canonicalPath

        ZipFile.builder().setFile(zipFile).get().use { zf ->
            for (zipEntry in zf.entries) {
                val newFile = File(destDir, zipEntry.name)

                // Zip Slip: refuse anything that escapes destDir.
                val destFilePath = newFile.canonicalPath
                if (!destFilePath.startsWith(destDirPath + File.separator)) {
                    logger.warn("Missed attempt to go outside the folder when unpacking: {}", zipEntry.name)
                    continue
                }

                // SmartyCraft modpacks ship plain files only; symlink
                // entries are either packaging accidents or hostile.
                if (zipEntry.isUnixSymlink) {
                    logger.warn("Refusing symlink entry from archive {}: {}", zipFile.name, zipEntry.name)
                    continue
                }

                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    zf.getInputStream(zipEntry).use { zis ->
                        FileOutputStream(newFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    extracted += zipEntry.name.replace('\\', '/')
                }
            }
        }
        return extracted
    }
}
