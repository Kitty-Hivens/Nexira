package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

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
        // Используем LinkedHashSet для сохранения порядка вставки, хотя финальная сортировка всё равно будет
        val allJars = LinkedHashSet<Path>()

        val modsDir = clientRoot.resolve("mods").toAbsolutePath()
        // Библиотеки теперь ищутся строго внутри папки клиента для обеспечения изоляции
        val libDir = clientRoot.resolve("libraries").toAbsolutePath()

        // 1. Агрегация путей из манифеста
        manifestProcessor.flattenManifest(manifest).keys.forEach { rawPath ->
            // Очистка и разрешение пути относительно корня клиента с учетом OS-специфичных разделителей
            val resolved = resolveSanitizedPath(clientRoot, rawPath)
            allJars.add(resolved)
        }

        // 2. Резервный вариант: Локальное сканирование (Legacy support).
        // Восстановлено для поддержки старых серверов или случаев с пустым манифестом.
        if (manifest.files.isEmpty()) {
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
                logger.warn("Не удалось просканировать локальную директорию библиотек (Legacy mode): ${e.message}")
            }
        }

        // Подготовка исключений (нормализуем пути для точного сравнения)
        val absoluteExcluded = excludedModules.map { libDir.resolve(it).toAbsolutePath() }.toSet()

        // 3. Фильтрация и Сортировка
        return allJars
            .asSequence()
            .map { it.toAbsolutePath() }
            .filter { path -> validateLibrary(path, modsDir, absoluteExcluded) }
            .filter {
                // ПРИМЕЧАНИЕ: Фильтрация по ОС здесь намеренно отключена.
                // Строгие серверы требуют полного classpath из манифеста (включая нативные библиотеки Windows/macOS)
                // для совпадения ожидаемой контрольной суммы, даже на Linux.
                true
            }
            .sortedWith(::compareLibraries)
            .map { it.toString() }
            .joinToString(File.pathSeparator)
    }

    /**
     * Проверяет, подходит ли библиотека для включения в classpath.
     */
    private fun validateLibrary(path: Path, modsDir: Path, excluded: Set<Path>): Boolean {
        val fileName = path.name.lowercase()
        val pathStr = path.toString()


        // 1. Проверка существования
        if (!path.exists()) {
            // Если файла нет, но это часть структуры библиотек - логируем WARN, но пропускаем файл,
            // чтобы не засорять classpath несуществующими путями, что может вызвать ошибки JVM.
            if (pathStr.contains("libraries")) {
                logger.warn("Библиотека из манифеста не найдена на диске, пропуск: $path")
            }
            return false
        }

        // 2. Проверка расширения (.jar или .zip)
        if (!fileName.endsWith(".jar") && !fileName.endsWith(".zip")) return false

        // 3. Исключение специальных папок (mods, config, assets)
        if (path.startsWith(modsDir)) return false

        // Используем безопасный Path API для проверки имен папок, вместо string.contains
        // Это предотвращает ложные срабатывания (например "my_config_lib.jar")
        for (part in path) {
            val partName = part.name
            if (partName == "config" || partName == "assets") return false
        }

        // 4. Явные исключения (из аргумента excludedModules)
        if (excluded.contains(path)) return false

        // 5. Фильтр целостности (Integrity Filter)
        // Исключаем локальные архивы, вызывающие несовпадение контрольных сумм
        if (internalBlacklist.contains(fileName)) return false
        if ((fileName.startsWith("assets-") || fileName.startsWith("natives-")) && fileName.endsWith(".zip")) {
            return false
        }

        return true
    }

    /**
     * Разрешает "сырой" путь из манифеста относительно корня клиента.
     * Безопасно обрабатывает разделители путей для Windows/Unix используя Path API.
     */
    private fun resolveSanitizedPath(root: Path, rawPath: String): Path {
        // Path.of / Paths.get автоматически обрабатывает сепараторы текущей ОС
        val pathPart = Paths.get(rawPath)

        // Логика удаления стандартных префиксов (чтобы избежать дублирования путей типа libraries/libraries/...)
        if (pathPart.nameCount > 1) {
            val first = pathPart.getName(0).toString()
            if (!first.startsWith("libraries") && first != "bin" && first != "mods") {
                // Безопасно берем под-путь, исключая первый сегмент
                return root.resolve(pathPart.subpath(1, pathPart.nameCount))
            }
        }
        return root.resolve(pathPart)
    }

    /**
     * Сортирует библиотеки для обеспечения детерминированного порядка загрузки классов.
     * Библиотеки начальной загрузки (Bootstrap/LaunchWrapper) должны загружаться первыми.
     */
    private fun compareLibraries(p1: Path, p2: Path): Int {
        val n1 = p1.name.lowercase()
        val n2 = p2.name.lowercase()

        val p1Priority = isBootstrapLibrary(n1)
        val p2Priority = isBootstrapLibrary(n2)

        return when {
            p1Priority && !p2Priority -> -1
            !p1Priority && p2Priority -> 1
            else -> p1.compareTo(p2)
        }
    }

    private fun isBootstrapLibrary(name: String): Boolean {
        return name.contains("launchwrapper") ||
                name.contains("bootstraplauncher") ||
                name.contains("asm-all")
    }
}