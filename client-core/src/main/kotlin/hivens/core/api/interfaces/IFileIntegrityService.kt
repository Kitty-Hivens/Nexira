package hivens.core.api.interfaces

import hivens.core.data.FileStatus
import java.io.IOException
import java.nio.file.Path

/**
 * Контракт для сервиса проверки целостности файлов.
 * Отвечает за вычисление хэшей и сравнение их с эталонными.
 */
interface IFileIntegrityService {

    /**
     * Вычисляет хэш-сумму (MD5 или SHA) для указанного файла.
     * Алгоритм (MD5/SHA1/SHA256) определяется реализацией.
     *
     * @param filePath Путь к файлу.
     * @return Строковое представление хэш-суммы (hex).
     * @throws IOException в случае ошибок I/O при чтении файла.
     */
    @Throws(IOException::class)
    fun calculateFileHash(filePath: Path): String

    /**
     * Проверяет статус одного файла относительно ожидаемого хэша.
     *
     * @param filePath Путь к файлу.
     * @param expectedHash Ожидаемый хэш (из ClientData).
     * @return FileStatus (MISSING, MISMATCH, VALID).
     * @throws IOException в случае ошибок I/O.
     */
    @Throws(IOException::class)
    fun checkFile(filePath: Path, expectedHash: String): FileStatus

    /**
     * Проверяет карту файлов (полученную из ClientData) и возвращает
     * список файлов, требующих обновления (статус MISSING или MISMATCH).
     *
     * @param basePath Корневая директория клиента (например, /home/user/.smarty).
     * @param filesToVerify Карта (относительный путь -> хэш) из ClientData.
     * @return Карта файлов, требующих загрузки (относительный путь -> хэш).
     */
    fun verifyIntegrity(basePath: Path, filesToVerify: Map<String, String>): Map<String, String>
}
