package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Компонент, отвечающий за сборку classpath для запуска JVM.
 * Фильтрует лишние файлы (конфиги, нативы, моды), чтобы локальный хеш совпадал с серверным.
 */
class ClasspathProvider(
    private val manifestProcessor: IManifestProcessorService
) {

    private val logger = LoggerFactory.getLogger(ClasspathProvider::class.java)

    // Файлы, которые генерируются лаунчером, но не должны попадать в classpath
    private val internalBlacklist = setOf("extra.zip", "assets.zip", "natives.zip")

    fun buildClasspath(
        clientRoot: Path,
        manifest: FileManifest,
        excludedModules: List<String>
    ): String {
        val allJars = LinkedHashSet<Path>()

        val modsDir = clientRoot.resolve("mods").toAbsolutePath()
        val libDir = clientRoot.resolve("libraries").toAbsolutePath()

        // 1. Берем пути из манифеста (Основной источник)
        val flatManifest = manifestProcessor.flattenManifest(manifest)
        flatManifest.keys.forEach { rawPath ->
            val resolved = resolveSanitizedPath(clientRoot, rawPath)
            allJars.add(resolved)
        }

        // 2. Fallback: Локальное сканирование (если манифест пуст или старый сервер)
        if (manifest.files.isEmpty() && manifest.directories.isEmpty()) {
            runCatching {
                if (Files.exists(libDir)) {
                    Files.walk(libDir).use { stream ->
                        stream.filter { path ->
                            val name = path.name.lowercase()
                            path.isRegularFile() && name.endsWith(".jar") && !name.endsWith(".cache")
                        }.forEach { allJars.add(it) }
                    }
                }
            }.onFailure { e ->
                logger.warn("Ошибка локального сканирования библиотек: ${e.message}")
            }
        }

        // Подготовка исключений (нормализуем пути)
        // excludedModules могут приходить как имена файлов или пути
        val absoluteExcludedNames = excludedModules.map { Paths.get(it).name.lowercase() }.toSet()

        // 3. Фильтрация и Сортировка
        return allJars
            .asSequence()
            .map { it.toAbsolutePath() }
            .filter { path ->
                validateLibrary(path, modsDir, absoluteExcludedNames)
            }
            // Сортировка важна! Если порядок разный, хеш будет разный.
            .sortedWith(::compareLibraries)
            .map { it.toString() }
            .joinToString(File.pathSeparator)
    }

    /**
     * Проверяет, подходит ли библиотека для включения в classpath.
     */
    private fun validateLibrary(path: Path, modsDir: Path, excludedNames: Set<String>): Boolean {
        val fileName = path.name.lowercase()

        // 1. Проверка существования
        if (!path.exists()) {
            // Если это библиотека, но её нет на диске — это проблема, но добавлять несуществующий путь нельзя
            if (path.toString().contains("libraries")) {
                logger.warn("Библиотека из манифеста отсутствует: $path")
            }
            return false
        }

        // 2. Только JAR и ZIP
        if (!fileName.endsWith(".jar") && !fileName.endsWith(".zip")) return false

        // 3. Исключаем папку mods и системные папки (config, assets)
        if (path.startsWith(modsDir)) return false

        for (part in path) {
            val n = part.name
            if (n == "config" || n == "assets") return false
        }

        // 4. Явные исключения (опциональные моды)
        if (excludedNames.contains(fileName)) return false

        // 5. Внутренний черный список (extra.zip и т.д.)
        if (internalBlacklist.contains(fileName)) return false
        if ((fileName.startsWith("assets-") || fileName.startsWith("natives-")) && fileName.endsWith(".zip")) {
            return false
        }

        return true
    }

    /**
     * Разрешает путь из манифеста относительно корня клиента.
     * Убирает префикс с именем сервера, если он есть (Legacy структура Smarty).
     */
    private fun resolveSanitizedPath(root: Path, rawPath: String): Path {
        val pathPart = Paths.get(rawPath)

        // Логика удаления префикса (имя сервера), если он есть в пути манифеста
        // Пример: "Nevermine/libraries/..." -> "libraries/..."
        if (pathPart.nameCount > 1) {
            val first = pathPart.getName(0).toString()
            if (!first.startsWith("libraries") && first != "bin" && first != "mods") {
                return root.resolve(pathPart.subpath(1, pathPart.nameCount))
            }
        }
        return root.resolve(pathPart)
    }

    /**
     * Сортировка для детерминированного порядка загрузки.
     * LaunchWrapper и Bootstrap должны быть первыми.
     */
    private fun compareLibraries(p1: Path, p2: Path): Int {
        val n1 = p1.name.lowercase()
        val n2 = p2.name.lowercase()

        val p1Priority = isBootstrapLibrary(n1)
        val p2Priority = isBootstrapLibrary(n2)

        return when {
            p1Priority && !p2Priority -> -1
            !p1Priority && p2Priority -> 1
            else -> p1.compareTo(p2) // Алфавитный порядок для остальных
        }
    }

    private fun isBootstrapLibrary(name: String): Boolean {
        return name.contains("launchwrapper") ||
                name.contains("bootstraplauncher") ||
                name.contains("asm-all")
    }
}
