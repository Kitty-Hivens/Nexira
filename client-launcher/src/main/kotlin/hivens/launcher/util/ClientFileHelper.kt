package hivens.launcher.util

import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Utility class for general operations with client files.
 * Eliminates code duplication between managers.
 */
object ClientFileHelper {

    /**
     * Safely creates a directory if it does not exist.
     */
    fun ensureDirectoryExists(dir: Path) {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
        }
    }

    /**
     * Clears the directory of all files except allowedFiles.
     * Used to synchronize the mods and natives folders.
     *
     * @param dir Target folder.
     * @param allowedFiles The set of file names to keep.
     * @param logger The calling class's logger for recording operations.
     */
    fun cleanDirectory(dir: Path, allowedFiles: Set<String>, logger: Logger) {
        if (!Files.exists(dir)) return

        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        val fileName = path.fileName.toString()
                        // We delete everything that is not in the white list and looks like executable files/archives
                        val isRelevantExtension = fileName.endsWith(".jar") || 
                                                  fileName.endsWith(".zip") || 
                                                  fileName.endsWith(".litemod") || 
                                                  fileName.endsWith(".dll") || 
                                                  fileName.endsWith(".so") || 
                                                  fileName.endsWith(".dylib")

                        if (isRelevantExtension && !allowedFiles.contains(fileName)) {
                            try {
                                Files.delete(path)
                                logger.debug("Deleted redundant file: {}", fileName)
                            } catch (e: IOException) {
                                logger.error("Failed to delete file: $fileName", e)
                            }
                        }
                    }
            }
        } catch (e: IOException) {
            logger.error("Error while cleaning directory: $dir", e)
        }
    }
}
