package hivens.core.api.interfaces

import hivens.core.data.SessionData
import java.io.IOException
import java.nio.file.Path

/**
 * Контракт для сервиса загрузки файлов клиента.
 */
interface IFileDownloadService {

    /**
     * Загружает один файл из удаленного источника.
     */
    @Throws(IOException::class)
    fun downloadFile(relativePath: String, destinationPath: Path)

    /**
     * Запускает полный процесс обработки сессии (парсинг манифеста, скачивание файлов, распаковка extra.zip).
     * * @param session Сессия пользователя (содержит манифест файлов).
     * @param serverId ID сервера (для логов).
     * @param targetDir Папка клиента.
     * @param extraCheckSum MD5 хеш для extra.zip (опционально).
     * @param ignoredFiles Список игнорируемых файлов (опционально).
     * @param messageUI Лямбда для сообщений UI (опционально).
     * @param progressUI Лямбда для прогресса (текущее, всего) (опционально).
     */
    @Throws(IOException::class)
    fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int) -> Unit)?
    )
}
