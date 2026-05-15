package hivens.core.util

import org.apache.commons.compress.archivers.zip.ZipFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

object ZipUtils {
    private val logger = LoggerFactory.getLogger(ZipUtils::class.java)

    /**
     * Unzips [zipFile] into [destDir]. Returns the relative paths of every
     * file (NOT directory) extracted, normalized to forward slashes, in
     * the order they appeared in the archive. Existing callers that
     * ignore the return value are unaffected.
     *
     * The path list is what powers `FileDownloadService`'s extra.zip
     * orphan-pruning — by snapshotting the contents of the previous
     * unpack we can diff against the new contents and remove files that
     * the upstream modpack dropped.
     *
     * ## Hardening (#187)
     * Plain Zip Slip protection (`startsWith(destDir)`) catches `../`
     * traversal in entry names but not symbolic-link entries. A symlink
     * entry named `safe.txt` whose payload is the path of `~/.ssh/id_rsa`
     * would normalize to a path INSIDE [destDir], pass the check, then
     * cause the launcher to write attacker-controlled bytes to the linked
     * target on the *next* extraction touching that name. We therefore
     * use [ZipFile] (random-access central-directory reader) instead of
     * the streaming variant: only the central directory carries the
     * external-attributes field that holds the unix file-type bits.
     */
    fun unzip(zipFile: File, destDir: File): List<String> {
        if (!destDir.exists()) destDir.mkdirs()
        val buffer = ByteArray(8192)
        val extracted = mutableListOf<String>()
        val destDirPath = destDir.canonicalPath

        ZipFile.builder().setFile(zipFile).get().use { zf ->
            for (zipEntry in zf.entries) {
                val newFile = File(destDir, zipEntry.name)

                // Zip Slip protection (preventing path traversal vulnerability)
                val destFilePath = newFile.canonicalPath
                if (!destFilePath.startsWith(destDirPath + File.separator)) {
                    logger.warn("Missed attempt to go outside the folder when unpacking: {}", zipEntry.name)
                    continue
                }

                // Symlink / non-regular-file rejection. SmartyCraft modpacks and
                // assets archives ship plain files only; anything else is either
                // a packaging accident or a hostile payload — refuse and skip.
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
