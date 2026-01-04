package hivens.launcher

import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.core.util.ZipUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

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

    override suspend fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val manifest = session.fileManifest ?: throw IOException("Манифест файлов пуст!")
        Files.createDirectories(targetDir)

        // 1. Получаем карту Path -> FileData
        val filesMap = flattenManifest(manifest)

        // 2. Фильтрация
        if (!ignoredFiles.isNullOrEmpty()) {
            filesMap.keys.removeIf { relativePath ->
                val clean = normalizePath(relativePath)
                ignoredFiles.any { clean.endsWith("/$it") || clean == it }
            }
            cleanupIgnoredFiles(targetDir, ignoredFiles)
        }

        // 3. Скачивание с подсчетом байтов
        downloadMissingFiles(targetDir, filesMap, messageUI, progressUI)

        // 4. Extra.zip (конфиги)
        processExtraZip(targetDir, filesMap, extraCheckSum, messageUI)
    }

    /**
     * Рекурсивно обходит манифест и собирает все файлы в одну карту.
     */
    private fun flattenManifest(manifest: FileManifest): MutableMap<String, FileData> {
        val result = HashMap<String, FileData>()
        fun traverse(m: FileManifest, currentPath: String) {
            m.files.forEach { (name, data) ->
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                result[fullPath] = data
            }
            m.directories.forEach { (name, subManifest) ->
                traverse(subManifest, if (currentPath.isEmpty()) name else "$currentPath/$name")
            }
        }
        traverse(manifest, "")
        return result
    }

    private suspend fun downloadMissingFiles(
        baseDir: Path,
        files: Map<String, FileData>,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?
    ) {
        // ЭТАП 1: Проверка хешей
        messageUI?.invoke("Сверка хеш-сумм...")

        // Тут происходит тяжелая работа (чтение файлов с диска)
        val filesToDownload = files.filter { (path, data) ->
            isFileMissingOrChanged(baseDir.resolve(normalizePath(path)), data.md5)
        }

        val totalFilesCount = filesToDownload.size
        // Считаем общий размер (если size нет, будет 0)
        val totalBytesToDownload = filesToDownload.values.sumOf { it.size }

        if (totalFilesCount == 0) {
            messageUI?.invoke("Файлы проверены, обновлений нет.")
            return
        }

        // ЭТАП 2: Скачивание
        messageUI?.invoke("Загрузка обновлений ($totalFilesCount файлов)...")

        // Атомики для потокобезопасного счета
        val currentFileCounter = AtomicInteger(0)
        val downloadedBytesGlobal = AtomicLong(0)
        val semaphore = Semaphore(5) // Ограничение в 5 потоков

        val startTime = System.currentTimeMillis()

        coroutineScope {
            // Тикер для UI
            val monitorJob = launch(Dispatchers.Main) {
                while (isActive) {
                    val currentBytes = downloadedBytesGlobal.get()
                    val currentFiles = currentFileCounter.get()

                    val now = System.currentTimeMillis()
                    val durationSec = (now - startTime) / 1000.0
                    val speed = if (durationSec > 0.1) formatSpeed(currentBytes / durationSec) else "..."

                    progressUI?.invoke(
                        currentFiles,
                        totalFilesCount,
                        currentBytes,
                        totalBytesToDownload,
                        speed
                    )

                    delay(100)
                    if (currentFiles >= totalFilesCount && currentBytes >= totalBytesToDownload) break
                }
            }

            // Скачивание
            val tasks = filesToDownload.map { (rawPath, _) ->
                async(Dispatchers.IO) {
                    if (!isActive) throw CancellationException()

                    semaphore.withPermit {
                        if (!isActive) throw CancellationException()

                        val cleanPath = normalizePath(rawPath)
                        val targetFile = baseDir.resolve(cleanPath)

                        downloadFileInternal(rawPath, targetFile) { bytesRead ->
                            // Просто увеличиваем счетчик. UI не трогаем.
                            downloadedBytesGlobal.addAndGet(bytesRead.toLong())
                            if (!isActive) throw CancellationException()
                        }

                        currentFileCounter.incrementAndGet()
                    }
                }
            }

            // Ждем завершения всех загрузок
            try {
                tasks.awaitAll()
            } finally {
                monitorJob.cancel()
            }

            // Финальный апдейт (100%)
            if (isActive) {
                progressUI?.invoke(
                    totalFilesCount, totalFilesCount,
                    totalBytesToDownload, totalBytesToDownload,
                    ""
                )
            }
        }
    }

    private suspend fun downloadFileInternal(
        serverPath: String,
        localPath: Path,
        onBytesRead: ((Int) -> Unit)? = null
    ) {
        val url = DOWNLOAD_BASE_URL + serverPath.replace(" ", "%20")
        withContext(Dispatchers.IO) {
            if (localPath.parent != null) Files.createDirectories(localPath.parent)

            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) throw IOException("HTTP ${response.status} for $url")

                val channel = response.bodyAsChannel()
                FileOutputStream(localPath.toFile()).use { output ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytesRead?.invoke(read)
                    }
                }
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val kb = bytesPerSec / 1024
        if (kb < 1024) return "${kb.roundToInt()} KB/s"
        val mb = kb / 1024
        return String.format("%.1f MB/s", mb)
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
        files: Map<String, FileData>,
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
