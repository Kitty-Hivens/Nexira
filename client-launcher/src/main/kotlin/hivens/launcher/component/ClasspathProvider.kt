package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.stream.Collectors
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Компонент, отвечающий за разрешение зависимостей classpath для JVM.
 *
 * Обеспечивает строгое соответствие манифесту сервера, сохраняя при этом
 * целостность локальных файлов и кроссплатформенную совместимость.
 */
internal class ClasspathProvider(
    private val manifestProcessor: IManifestProcessorService
) {

    private val logger = LoggerFactory.getLogger(ClasspathProvider::class.java)

    // Внутренний черный список файлов, которые генерируются лаунчером локально,
    // но не должны попадать в classpath для сохранения согласованности хеша с сервером.
    private val internalBlacklist = setOf("extra.zip", "assets.zip", "natives.zip")

    fun buildClasspath(
        clientRoot: Path,
        manifest: FileManifest,
        excludedModules: List<String>
    ): String {
        val allJars = HashSet<Path>()
        val modsDir = clientRoot.resolve("mods").toAbsolutePath()
        // Библиотеки теперь ищутся строго внутри папки клиента для обеспечения изоляции
        val libDir = clientRoot.resolve("libraries").toAbsolutePath()

        // 1. Агрегация путей из манифеста
        manifestProcessor.flattenManifest(manifest).keys.forEach { rawPath ->
            // Очистка и разрешение пути относительно корня клиента
            val resolved = resolveSanitizedPath(clientRoot, rawPath)
            allJars.add(resolved)
        }

        // 2. Резервный вариант: Локальное сканирование (восстановлена поддержка Legacy)
        if (manifest.files.isEmpty()) {
            runCatching {
                if (Files.exists(libDir)) {
                    Files.walk(libDir).use { walk ->
                        walk.filter {
                            val name = it.name.lowercase()
                            Files.isRegularFile(it) && name.endsWith(".jar") && !name.endsWith(".cache")
                        }.forEach { allJars.add(it) }
                    }
                }
            }.onFailure { e ->
                logger.warn("Не удалось просканировать локальную директорию библиотек: ${e.message}")
            }
        }

        // Подготовка исключений
        val absoluteExcluded = excludedModules.map { libDir.resolve(it).toAbsolutePath() }

        // 3. Фильтрация и Сортировка
        return allJars.stream()
            .map { it.toAbsolutePath() }
            .filter { path ->
                val fileName = path.name.lowercase()
                val pathStr = path.toString()

                // Базовая валидация существования
                if (!Files.exists(path)) {
                    // WARN: Допускаем отсутствующие библиотеки, если они часть структуры
                    // (некоторые серверы проверяют хеш даже отсутствующих файлов), но пишем предупреждение.
                    if (!pathStr.contains("libraries")) return@filter false
                }

                // Проверка расширения
                if (!fileName.endsWith(".jar") && !fileName.endsWith(".zip")) return@filter false

                // Исключение папок модов и конфигов/ассетов
                if (path.startsWith(modsDir)) return@filter false
                if (pathStr.contains("${File.separator}config${File.separator}") ||
                    pathStr.contains("${File.separator}assets${File.separator}")) return@filter false

                // Явные исключения (из аргумента excludedModules)
                if (absoluteExcluded.contains(path)) return@filter false

                // --- ФИЛЬТР ЦЕЛОСТНОСТИ (INTEGRITY FILTER) ---
                // Исключаем локальные архивы, вызывающие несовпадение контрольных сумм на строгих серверах
                if (internalBlacklist.contains(fileName)) return@filter false
                if ((fileName.startsWith("assets-") || fileName.startsWith("natives-")) && fileName.endsWith(".zip")) {
                    return@filter false
                }

                true
            }
            .filter { path ->
                // ПРИМЕЧАНИЕ: Фильтрация по ОС здесь намеренно отключена.
                // Строгие серверы требуют полного classpath из манифеста (включая нативные библиотеки Windows/macOS)
                // для совпадения ожидаемой контрольной суммы, даже на Linux.
                // Мы загружаем всё для обеспечения подключения.
                true
            }
            .map { it.toString() }
            .sorted(::sortLibraries) // Восстановлен детерминированный порядок (сортировка)
            .collect(Collectors.joining(File.pathSeparator))
    }

    /**
     * Разрешает "сырой" путь из манифеста относительно корня клиента.
     * Безопасно обрабатывает разделители путей для Windows/Unix.
     */
    private fun resolveSanitizedPath(root: Path, rawPath: String): Path {
        // Нормализация разделителей под текущую систему
        val normalized = rawPath.replace("/", File.separator).replace("\\", File.separator)

        // Удаление стандартных префиксов, чтобы избежать дублирования путей
        val parts = normalized.split(File.separator)
        if (parts.size > 1) {
            val first = parts[0]
            if (!first.startsWith("libraries") && first != "bin" && first != "mods") {
                return root.resolve(normalized.substringAfter(File.separator))
            }
        }
        return root.resolve(normalized)
    }

    /**
     * Сортирует библиотеки для обеспечения детерминированного порядка загрузки классов.
     * Библиотеки начальной загрузки (Bootstrap/LaunchWrapper) должны загружаться первыми.
     */
    private fun sortLibraries(p1: String, p2: String): Int {
        val p1Name = File(p1).name.lowercase()
        val p2Name = File(p2).name.lowercase()

        val p1Priority = isBootstrapLibrary(p1Name)
        val p2Priority = isBootstrapLibrary(p2Name)

        return when {
            p1Priority && !p2Priority -> -1
            !p1Priority && p2Priority -> 1
            else -> p1.compareTo(p2) // Лексикографическая сортировка для стабильности
        }
    }

    private fun isBootstrapLibrary(name: String): Boolean {
        return name.contains("launchwrapper") ||
                name.contains("bootstraplauncher") ||
                name.contains("asm-all")
    }
}