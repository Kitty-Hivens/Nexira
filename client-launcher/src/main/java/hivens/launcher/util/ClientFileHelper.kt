package hivens.launcher.util

import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Утилитарный класс для общих операций с файлами клиента.
 * Устраняет дублирование кода между менеджерами.
 */
object ClientFileHelper {

    /**
     * Безопасно создает директорию, если она не существует.
     */
    fun ensureDirectoryExists(dir: Path) {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
        }
    }

    /**
     * Очищает директорию от всех файлов, кроме разрешенных (allowedFiles).
     * Используется для синхронизации папки mods и natives.
     *
     * @param dir Целевая папка.
     * @param allowedFiles Набор имен файлов, которые нужно оставить.
     * @param logger Логгер вызывающего класса для записи операций.
     */
    fun cleanDirectory(dir: Path, allowedFiles: Set<String>, logger: Logger) {
        if (!Files.exists(dir)) return

        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        val fileName = path.fileName.toString()
                        // Удаляем всё, что не в белом списке и похоже на исполняемые файлы/архивы
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
