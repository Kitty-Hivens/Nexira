package hivens.launcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.data.SessionData
import hivens.core.util.ZipUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class FileDownloadService(
    private val client: OkHttpClient,
    private val gson: Gson
) : IFileDownloadService {

    companion object {
        private val logger = LoggerFactory.getLogger(FileDownloadService::class.java)
        private const val DOWNLOAD_BASE_URL = "https://www.smartycraft.ru/launcher/clients/"

        /**
         * Список стандартных папок Minecraft.
         */
        private val STANDARD_DIRS = setOf(
            "mods", "config", "bin", "assets", "libraries", "libraries-1.12.2", "libraries-1.7.10",
            "scripts", "resources", "saves", "resourcepacks", "shaderpacks", "texturepacks", "coremods", "natives"
        )
    }

    @Throws(IOException::class)
    override fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int) -> Unit)?
    ) {
        logger.info("Начало загрузки сессии для: {} -> {}", serverId, targetDir)

        val clientJson = gson.toJsonTree(session.fileManifest)
        if (!clientJson.isJsonObject) throw IOException("Ошибка манифеста: не JSON объект")

        Files.createDirectories(targetDir)

        val filesToDownload = HashMap<String, String>()
        flattenJsonTree(clientJson.asJsonObject, "", filesToDownload)

        // Фильтрация модов
        if (!ignoredFiles.isNullOrEmpty()) {
            val before = filesToDownload.size
            filesToDownload.entries.removeIf { entry ->
                val cleanPath = sanitizePath(entry.key)
                ignoredFiles.any { ignoredName -> 
                    cleanPath.endsWith("/$ignoredName") || cleanPath == ignoredName 
                }
            }
            logger.info("Отфильтровано файлов: {}", before - filesToDownload.size)
        }

        logger.info("Файлов к загрузке: {}", filesToDownload.size)
        messageUI?.invoke("Проверка файлов...")

        val downloaded = downloadFiles(targetDir, filesToDownload, messageUI, progressUI)

        // Обработка extra.zip (Конфиги)
        handleExtraZip(targetDir, filesToDownload, extraCheckSum, messageUI)

        logger.info("Загрузка завершена. Скачано: {}", downloaded)
        messageUI?.invoke("Готово! Обновление завершено.")
        progressUI?.invoke(filesToDownload.size, filesToDownload.size)
    }

    /**
     * Превращает "Industrial/mods/jei.jar" -> "mods/jei.jar"
     */
    private fun sanitizePath(rawPath: String?): String {
        if (rawPath.isNullOrEmpty()) return rawPath ?: ""

        // Разбиваем путь на части
        val parts = rawPath.split("/")

        // Если путь состоит из 1 части ("file.txt") - оставляем
        if (parts.size < 2) return rawPath

        val firstDir = parts[0]

        // Если первая папка — это "bin", "mods" и т.д. — оставляем как есть.
        if (STANDARD_DIRS.contains(firstDir) || firstDir.startsWith("natives") || firstDir.startsWith("libraries")) {
            return rawPath
        }

        // Иначе считаем, что это "Industrial" или другой мусорный префикс.
        // Отрезаем его: берем подстроку после первого слэша.
        return rawPath.substring(firstDir.length + 1)
    }

    private fun handleExtraZip(
        targetDir: Path,
        allFiles: Map<String, String>,
        serverProfileExtraCheckSum: String?,
        messageUI: ((String) -> Unit)?
    ) {
        // Ищем extra.zip (теперь проверяем с учетом sanitizePath)
        val extraKey = allFiles.keys.firstOrNull { k -> sanitizePath(k).endsWith("extra.zip") }
            ?: return

        // Важно: скачанный файл лежит по "чистому" пути
        val localZipPath = targetDir.resolve(sanitizePath(extraKey))

        var needUnzip = false
        if (Files.exists(localZipPath)) {
            if (!serverProfileExtraCheckSum.isNullOrEmpty()) {
                try {
                    val localHash = getFileChecksum(localZipPath)
                    if (!localHash.equals(serverProfileExtraCheckSum, ignoreCase = true)) {
                        needUnzip = true
                    }
                } catch (e: Exception) {
                    needUnzip = true
                }
            }
        } else {
            needUnzip = true
        }

        if (needUnzip) {
            messageUI?.invoke("Применение настроек...")
            // Ждем завершения скачивания (файл мог еще не докачаться в потоке, но здесь мы после downloadFiles)
            if (Files.exists(localZipPath)) {
                try {
                    // Распаковываем в корень клиента
                    ZipUtils.unzip(localZipPath.toFile(), targetDir.toFile())
                } catch (e: IOException) {
                    logger.error("Ошибка распаковки extra.zip", e)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun downloadFiles(
        basePath: Path,
        filesToDownload: Map<String, String>,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int) -> Unit)?
    ): Int {
        val downloadedCount = AtomicInteger(0)
        val total = filesToDownload.size
        val current = AtomicInteger(0)

        for ((rawPath, expectedMd5) in filesToDownload) {
            progressUI?.invoke(current.get(), total)

            // Определяем правильный локальный путь (без "Industrial")
            val cleanPath = sanitizePath(rawPath)
            val targetFile = basePath.resolve(cleanPath)

            // UI
            if (messageUI != null && (current.get() % 10 == 0 || current.get() == total)) {
                messageUI(buildString {
                    append("Загрузка: ")
                    append(shortPath(cleanPath))
                })
            }

            if (needDownload(targetFile, expectedMd5)) {
                try {
                    downloadFile(rawPath, targetFile) // Качаем raw -> кладем в clean
                    downloadedCount.incrementAndGet()
                } catch (e: IOException) {
                    logger.error("Ошибка скачивания: {}", rawPath, e)
                }
            }
            current.incrementAndGet()
        }
        return downloadedCount.get()
    }

    @Throws(IOException::class)
    override fun downloadFile(relativePath: String, destinationPath: Path) {
        val fileUrl = getFileUrl(relativePath)
        if (destinationPath.parent != null) Files.createDirectories(destinationPath.parent)

        val request = Request.Builder().url(fileUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            val body = response.body ?: throw IOException("Empty body")

            body.byteStream().use { input ->
                FileOutputStream(destinationPath.toFile()).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun getFileUrl(relativePath: String): String {
        // Здесь relativePath - это "Industrial/mods/..." (rawPath)
        return DOWNLOAD_BASE_URL + relativePath.replace(" ", "%20")
    }

    private fun needDownload(file: Path, expectedMd5: String?): Boolean {
        if (!Files.exists(file)) return true
        if (Files.isDirectory(file)) return false
        if (expectedMd5 == null || "any".equals(expectedMd5, ignoreCase = true)) return false
        return try {
            if (Files.size(file) == 0L) return true
            val localMd5 = getFileChecksum(file)
            !localMd5.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            true
        }
    }

    private fun flattenJsonTree(dirObject: JsonObject, currentPath: String, filesMap: MutableMap<String, String>) {
        if (dirObject.has("files")) {
            val files = dirObject.getAsJsonObject("files")
            for ((fileName, value) in files.entrySet()) {
                val fileInfo = value.asJsonObject
                val md5 = if (fileInfo.has("md5")) fileInfo.get("md5").asString else "any"
                val relPath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
                filesMap[relPath] = md5
            }
        }
        if (dirObject.has("directories")) {
            val directories = dirObject.getAsJsonObject("directories")
            for ((dirName, value) in directories.entrySet()) {
                flattenJsonTree(
                    value.asJsonObject,
                    if (currentPath.isEmpty()) dirName else "$currentPath/$dirName",
                    filesMap
                )
            }
        }
    }

    @Throws(Exception::class)
    private fun getFileChecksum(file: Path): String {
        val md = MessageDigest.getInstance("MD5")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        val digest = md.digest()
        // Быстрая конвертация в hex
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun shortPath(path: String): String {
        return if (path.length > 40) "..." + path.substring(path.length - 40) else path
    }
}
