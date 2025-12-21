package hivens.launcher.component

import hivens.core.util.ZipUtils
import hivens.launcher.util.ClientFileHelper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.stream.Collectors

class EnvironmentPreparer {
    private val log = LoggerFactory.getLogger(EnvironmentPreparer::class.java)

    // Модули для Modern версий (1.13+)
    private val lwjgl3Modules = listOf(
        "lwjgl", "lwjgl-jemalloc", "lwjgl-openal", "lwjgl-opengl",
        "lwjgl-glfw", "lwjgl-stb", "lwjgl-tinyfd"
    )
    private val lwjgl3Version = "3.3.3"

    fun prepareNatives(clientRoot: Path, nativesDirName: String, version: String) {
        val binDir = clientRoot.resolve("bin")
        val nativesDir = clientRoot.resolve(nativesDirName)
        val osSuffix = getOsSuffix()

        // 1. Проверка: Если папка валидна, ничего не делаем
        if (isFolderValidForOs(nativesDir, osSuffix)) {
            log.info("Natives valid for $osSuffix ($version).")
            return
        }

        // Чистим папку перед новой попыткой
        if (Files.exists(nativesDir)) {
            ClientFileHelper.cleanDirectory(nativesDir, emptySet(), log)
        }
        ClientFileHelper.ensureDirectoryExists(nativesDir)

        log.info("Preparing natives for $version ($osSuffix)...")

        // 2. Попытка найти локальный Zip (от сервера)
        val targetZipName = "natives-$version-$osSuffix.zip"
        val genericZipName = "natives-$version.zip"

        var nativesZip = binDir.resolve(targetZipName)
        if (!Files.exists(nativesZip)) {
            val generic = binDir.resolve(genericZipName)
            if (Files.exists(generic)) nativesZip = generic
        }

        var unpackedSuccessfully = false
        if (Files.exists(nativesZip)) {
            log.info("Found local zip: ${nativesZip.fileName}. Unpacking...")
            try {
                ZipUtils.unzip(nativesZip.toFile(), nativesDir.toFile())
                flattenNatives(nativesDir)

                if (isFolderValidForOs(nativesDir, osSuffix)) {
                    unpackedSuccessfully = true
                } else {
                    log.warn("Local zip content was invalid/empty for $osSuffix. Cleaning...")
                    ClientFileHelper.cleanDirectory(nativesDir, emptySet(), log)
                }
            } catch (e: Exception) {
                log.error("Failed to unzip local natives", e)
            }
        }

        // 3. Фолбек на Maven (если локального нет или он битый)
        if (!unpackedSuccessfully) {
            log.warn("Natives missing. Downloading correct version from Maven Central...")

            // !!! ГЛАВНОЕ ИСПРАВЛЕНИЕ: Выбор версии !!!
            if (version == "1.7.10" || version.startsWith("1.7.")) {
                downloadLegacyLWJGL2(nativesDir, osSuffix, "2.9.1")
            } else if (version == "1.12.2" || version.startsWith("1.12.")) {
                downloadLegacyLWJGL2(nativesDir, osSuffix, "2.9.4-nightly-20150209")
            } else {
                downloadModernLWJGL3(nativesDir, osSuffix)
            }

            flattenNatives(nativesDir)

            if (!isFolderValidForOs(nativesDir, osSuffix)) {
                log.error("CRITICAL: Failed to provide natives via Maven!")
            } else {
                log.info("Natives downloaded and unpacked successfully.")
            }
        }
    }

    /**
     * Скачивание для СТАРЫХ версий (1.7.10, 1.12.2) -> LWJGL 2
     */
    private fun downloadLegacyLWJGL2(destDir: Path, os: String, version: String) {
        val mavenOs = if (os == "macos") "macosx" else os // LWJGL 2 использовал 'macosx'

        // Список файлов для LWJGL 2
        val artifacts = mapOf(
            "https://repo1.maven.org/maven2/org/lwjgl/lwjgl/lwjgl-platform/$version/lwjgl-platform-$version-natives-$mavenOs.jar" to "lwjgl_platform",
            "https://repo1.maven.org/maven2/net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-$mavenOs.jar" to "jinput_platform"
        )

        log.info("Detected Legacy Minecraft ($version). Downloading LWJGL 2 natives...")

        artifacts.keys.forEach { url ->
            downloadAndUnzip(url, destDir)
        }
    }

    /**
     * Скачивание для НОВЫХ версий (1.13+) -> LWJGL 3
     */
    private fun downloadModernLWJGL3(destDir: Path, os: String) {
        val mavenOsClassifier = "natives-$os"
        val baseUrl = "https://repo1.maven.org/maven2/org/lwjgl"

        log.info("Detected Modern Minecraft. Downloading LWJGL $lwjgl3Version natives...")

        for (module in lwjgl3Modules) {
            val fileName = "$module-$lwjgl3Version-$mavenOsClassifier.jar"
            val url = "$baseUrl/$module/$lwjgl3Version/$fileName"
            downloadAndUnzip(url, destDir)
        }
    }

    // Утилитный метод для скачивания и распаковки
    private fun downloadAndUnzip(urlStr: String, destDir: Path) {
        try {
            log.info("Downloading: $urlStr")
            val tempJar = Files.createTempFile("aura_native_", ".jar")
            URL(urlStr).openStream().use { input ->
                Files.copy(input, tempJar, StandardCopyOption.REPLACE_EXISTING)
            }
            ZipUtils.unzip(tempJar.toFile(), destDir.toFile())
            Files.delete(tempJar)
        } catch (e: Exception) {
            log.error("Failed to fetch/unzip: $urlStr", e)
        }
    }

    /**
     * Вытаскивает .so/.dll в корень папки, если они лежат в подпапках
     */
    private fun flattenNatives(dir: Path) {
        try {
            if (!Files.exists(dir)) return
            val libraries = Files.walk(dir)
                .filter { Files.isRegularFile(it) }
                .filter {
                    val name = it.fileName.toString()
                    name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
                }
                .collect(Collectors.toList())

            for (lib in libraries) {
                val target = dir.resolve(lib.fileName)
                if (lib.parent != dir) {
                    Files.move(lib, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to flatten natives directory", e)
        }
    }

    private fun isFolderValidForOs(dir: Path, os: String): Boolean {
        if (!Files.exists(dir)) return false
        val expectedExtension = when (os) {
            "linux" -> ".so"
            "windows" -> ".dll"
            "macos" -> ".dylib"
            else -> return false
        }
        return try {
            Files.list(dir).use { stream ->
                stream.anyMatch { it.toString().lowercase().endsWith(expectedExtension) }
            }
        } catch (e: Exception) { false }
    }

    fun prepareAssets(clientRoot: Path, assetsZipName: String) {
        val assetsDir = clientRoot.resolve("assets")
        val objectsDir = assetsDir.resolve("objects")
        val assetsZip = clientRoot.resolve(assetsZipName)

        var needUnzip = false
        if (Files.exists(assetsZip)) {
            // Если папки assets нет или в objects пустовато - распаковываем
            if (!Files.exists(assetsDir) || !Files.exists(objectsDir)) {
                needUnzip = true
            } else {
                try {
                    // Грубая проверка: если файлов мало, значит распаковка была кривой
                    if (Files.list(objectsDir).count() < 10) needUnzip = true
                } catch (e: Exception) { needUnzip = true }
            }
        } else {
            // Если запрошенного архива нет, попробуем найти стандартный assets.zip
            // (фикс вашей проблемы с 1.7.10)
            val fallbackZip = clientRoot.resolve("assets.zip")
            if (Files.exists(fallbackZip)) {
                log.info("Requested $assetsZipName not found, but assets.zip exists. Using fallback.")
                // Рекурсивный вызов, но с правильным именем
                prepareAssets(clientRoot, "assets.zip")
                return
            }
        }

        if (needUnzip) {
            log.info("Unpacking assets archive: $assetsZipName...")
            try {
                ClientFileHelper.ensureDirectoryExists(assetsDir)
                ZipUtils.unzip(assetsZip.toFile(), assetsDir.toFile())
            } catch (e: IOException) {
                log.error("Failed to unzip assets", e)
            }
        }
    }

    private fun getOsSuffix(): String {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> "linux"
            else -> "unknown"
        }
    }
}
