package hivens.launcher

import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.core.util.ZipUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class FileDownloadService(
    private val client: HttpClient
) : IFileDownloadService {

    companion object {
        private val logger = LoggerFactory.getLogger(FileDownloadService::class.java)
        private const val DOWNLOAD_BASE_URL = "https://www.smartycraft.ru/launcher/clients/"

        // Папки, которые нельзя "обрезать" при нормализации путей
        private val ROOT_DIRS = setOf(
            "mods", "config", "bin", "assets", "libraries", "resources",
            "saves", "resourcepacks", "shaderpacks", "natives"
        )
    }

    override fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int) -> Unit)?
    ) {
        val manifest = session.fileManifest ?: throw IOException("Манифест файлов пуст!")
        logger.info("Начало обновления клиента: $serverId")

        Files.createDirectories(targetDir)

        // 1. Превращаем дерево манифеста в плоский список файлов (Path -> MD5)
        val filesMap = flattenManifest(manifest)

        // 2. Фильтрация игнорируемых файлов (опциональные моды и т.д.)
        if (!ignoredFiles.isNullOrEmpty()) {
            filesMap.keys.removeIf { relativePath ->
                val clean = normalizePath(relativePath)
                ignoredFiles.any { clean.endsWith("/$it") || clean == it }
            }

            // физическое удаление
            cleanupIgnoredFiles(targetDir, ignoredFiles)
        }

        logger.info("Файлов к проверке: ${filesMap.size}")
        messageUI?.invoke("Проверка файлов...")

        // 3. Скачивание
        runBlocking {
            downloadMissingFiles(targetDir, filesMap, messageUI, progressUI)
        }

        // 4. Обработка Extra.zip (конфиги)
        processExtraZip(targetDir, filesMap, extraCheckSum, messageUI)

        messageUI?.invoke("Готово!")
    }

    /**
     * Рекурсивно обходит манифест и собирает все файлы в одну карту.
     */
    private fun flattenManifest(manifest: FileManifest): MutableMap<String, String> {
        val result = HashMap<String, String>()

        fun traverse(m: FileManifest, currentPath: String) {
            // Файлы
            m.files.forEach { (name, data) ->
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                result[fullPath] = data.md5
            }
            // Папки
            m.directories.forEach { (name, subManifest) ->
                val nextPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                traverse(subManifest, nextPath)
            }
        }

        traverse(manifest, "")
        return result
    }

    private suspend fun downloadMissingFiles(
        baseDir: Path,
        files: Map<String, String>,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int) -> Unit)?
    ) {
        val total = files.size
        val currentCounter = AtomicInteger(0)
        // Ограничиваем кол-во одновременных загрузок
        val semaphore = Semaphore(5)

        coroutineScope {
            val tasks = files.map { (rawPath, expectedHash) ->
                async(Dispatchers.IO) {
                    // Ждем свободный слот в семафоре
                    semaphore.withPermit {
                        try {
                            val cleanPath = normalizePath(rawPath)
                            val targetFile = baseDir.resolve(cleanPath)

                            // Обновляем UI (прогресс бар)
                            val current = currentCounter.incrementAndGet()
                            progressUI?.invoke(current, total)

                            // Обновляем текст (реже, чтобы не мигало)
                            if (current % 5 == 0) messageUI?.invoke("Загрузка: ${shorten(cleanPath)}")

                            if (isFileMissingOrChanged(targetFile, expectedHash)) {
                                downloadFileInternal(rawPath, targetFile)
                            }
                        } catch (e: Exception) {
                            logger.error("Ошибка скачивания: $rawPath", e)
                            throw e // Пробрасываем ошибку, чтобы остановить процесс
                        }
                    }
                }
            }
            // Ждем завершения всех загрузок
            tasks.awaitAll()
        }
    }

    private suspend fun downloadFileInternal(serverPath: String, localPath: Path) {
        val url = DOWNLOAD_BASE_URL + serverPath.replace(" ", "%20")

        withContext(Dispatchers.IO) {
            if (localPath.parent != null) Files.createDirectories(localPath.parent)

            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) throw IOException("HTTP ${response.status} for $url")

                // Стримим байты прямо в файл
                val channel = response.bodyAsChannel()
                FileOutputStream(localPath.toFile()).use { output ->
                    channel.copyTo(output)
                }
            }
        }
    }

    // Для совместимости с интерфейсом (если вызывается отдельно)
    override fun downloadFile(relativePath: String, destinationPath: Path) {
        runBlocking { downloadFileInternal(relativePath, destinationPath) }
    }

    /**
     * Проверяет, нужно ли качать файл (нет файла, пустой, или хеш не совпал).
     */
    private fun isFileMissingOrChanged(file: Path, expectedMd5: String): Boolean {
        if (!Files.exists(file)) return true
        if (Files.isDirectory(file)) return false
        if (expectedMd5 == "any") return false // "any" хеш означает "не проверять"

        return try {
            if (Files.size(file) == 0L) return true
            val localMd5 = calculateMD5(file)
            !localMd5.equals(expectedMd5, ignoreCase = true)
        } catch (_: Exception) {
            true // При любой ошибке чтения лучше перекачать
        }
    }

    private fun processExtraZip(
        baseDir: Path,
        files: Map<String, String>,
        serverCheckSum: String?,
        messageUI: ((String) -> Unit)?
    ) {
        val extraKey = files.keys.firstOrNull { normalizePath(it).endsWith("extra.zip") } ?: return
        val localZip = baseDir.resolve(normalizePath(extraKey))

        if (Files.exists(localZip)) {
            var needUnzip = true

            // Если сервер прислал хеш для проверки конфигов (extraCheckSum)
            if (!serverCheckSum.isNullOrEmpty()) {
                val localHash = try {
                    calculateMD5(localZip)
                } catch(_: Exception) { "" }

                // Если хеш на диске совпадает с тем, что требует сервер профиля — распаковка не нужна
                if (localHash.equals(serverCheckSum, ignoreCase = true)) {
                    needUnzip = false
                }
            }

            if (needUnzip) {
                try {
                    messageUI?.invoke("Настройка клиента...")
                    ZipUtils.unzip(localZip.toFile(), baseDir.toFile())
                } catch (e: Exception) {
                    logger.error("Ошибка распаковки extra.zip", e)
                }
            }
        }
    }

    /**
     * Убирает префиксы типа "Industrial/mods/..." -> "mods/..."
     */
    private fun normalizePath(rawPath: String): String {
        val parts = rawPath.split("/")
        if (parts.size < 2) return rawPath

        // Если первая часть пути похожа на стандартную папку, оставляем как есть
        val root = parts[0]
        if (ROOT_DIRS.any { root.startsWith(it) }) return rawPath

        // Иначе отрезаем первую папку (это имя сервера/сборки)
        return rawPath.substring(root.length + 1)
    }

    private fun calculateMD5(file: Path): String {
        val md = MessageDigest.getInstance("MD5")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shorten(p: String) = if (p.length > 30) "..." + p.takeLast(30) else p

    private fun cleanupIgnoredFiles(baseDir: Path, ignoredFiles: Set<String>) {
        if (ignoredFiles.isEmpty()) return

        val modsDir = baseDir.resolve("mods")
        var deletedCount = 0

        if (Files.exists(modsDir)) {
            try {
                // Рекурсивный поиск файлов для удаления
                Files.walk(modsDir)
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        val fileName = file.fileName.toString()
                        if (ignoredFiles.contains(fileName)) {
                            try {
                                Files.delete(file)
                                deletedCount++
                            } catch (e: Exception) {
                                logger.warn("Не удалось удалить отключенный мод: $fileName", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.error("Ошибка очистки папки mods: ${e.message}")
            }
        }

        if (deletedCount > 0) {
            logger.info("Очистка клиента: удалено $deletedCount выключенных модов.")
        }
    }
}
