package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.stream.Collectors

/**
 * Компонент, реализующий стратегию разрешения зависимостей (Dependency Resolution) для JVM.
 *
 * Отвечает за формирование корректного аргумента `-classpath`, учитывая:
 * * Целостность файловой системы (проверка существования файлов).
 * * Совместимость нативных библиотек с текущей платформой (OS-specific natives).
 * * Приоритетность загрузчиков классов (ClassLoader priority).
 * * Исключение конфликтующих модулей (Blacklisting).
 */
internal class ClasspathProvider(
    private val manifestProcessor: IManifestProcessorService
) {

    /**
     * Строит строку путей к классам (Classpath String).
     *
     * **Алгоритм работы:**
     * 1. Агрегация путей из манифеста файлов.
     * 2. (Опционально) Рекурсивное сканирование локальной директории библиотек при отсутствии манифеста.
     * 3. Фильтрация несуществующих файлов.
     * 4. Фильтрация библиотек, несовместимых с текущей архитектурой ОС.
     * 5. Исключение модулей из списка [excludedModules].
     * 6. Лексикографическая сортировка с приоритетом для бутстрап-загрузчиков.
     *
     * @param clientRoot Корневая директория экземпляра клиента.
     * @param manifest Объект манифеста, содержащий список ожидаемых файлов.
     * @param excludedModules Список имен файлов, подлежащих принудительному исключению из classpath.
     *
     * @return Строка путей, разделенная системным разделителем [File.pathSeparator].
     */
    fun buildClasspath(
        clientRoot: Path,
        manifest: FileManifest,
        excludedModules: List<String>
    ): String {
        val currentOs = getPlatform()
        val allJars = HashSet<Path>()
        val modsDir = clientRoot.resolve("mods").toAbsolutePath()
        val libDir = clientRoot.resolve("libraries-1.21.1").toAbsolutePath()

        // 1. Агрегация из манифеста
        manifestProcessor.flattenManifest(manifest).keys.forEach { pathStr ->
            allJars.add(clientRoot.resolve(pathStr))
        }

        // 2. Fallback: Локальное сканирование (Legacy Support)
        if (manifest.files.isEmpty()) {
            runCatching {
                if (Files.exists(libDir)) {
                    Files.walk(libDir).use { walk ->
                        walk.filter {
                            val name = it.toString().lowercase()
                            Files.isRegularFile(it) && name.endsWith(".jar") && !name.endsWith(".cache")
                        }.forEach { allJars.add(it) }
                    }
                }
            }
        }

        val absoluteExcluded = excludedModules.map { libDir.resolve(it).toAbsolutePath() }

        // 3. Фильтрация и Сортировка
        return allJars.stream()
            .map { it.toAbsolutePath() }
            .filter { Files.exists(it) }
            .filter { path ->
                val fileName = path.fileName.toString().lowercase()
                /*
                 * Фильтр исключений:
                 * - Глобальный черный список.
                 * - Папка mods (загружается через ModDirTransformer/FML).
                 * - Специфичные JarJar модули.
                 */
                if (absoluteExcluded.contains(path)) return@filter false
                if (path.startsWith(modsDir)) return@filter false
                if (fileName.startsWith("neoforge-")) return@filter false
                if (fileName.startsWith("client-1.21.1")) return@filter false
                
                true
            }
            .map { it.toString() }
            .filter { isLibraryCompatibleWithOs(it, currentOs) }
            .sorted(::sortLibraries)
            .collect(Collectors.joining(File.pathSeparator))
    }

    /**
     * Определяет приоритет загрузки библиотек.
     *
     * **Контракт:** Библиотеки, содержащие `LaunchWrapper` или `BootstrapLauncher`,
     * должны быть первыми в списке для корректной инициализации загрузчика классов Minecraft.
     */
    private fun sortLibraries(p1: String, p2: String): Int {
        val p1Lower = p1.lowercase()
        val p2Lower = p2.lowercase()
        val p1Boots = p1Lower.contains("bootstraplauncher") || p1Lower.contains("launchwrapper")
        val p2Boots = p2Lower.contains("bootstraplauncher") || p2Lower.contains("launchwrapper")
        
        if (p1Boots && !p2Boots) return -1
        if (!p1Boots && p2Boots) return 1
        return p1.compareTo(p2)
    }

    private enum class OS { WINDOWS, LINUX, MACOS, UNKNOWN }

    private fun getPlatform(): OS {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("mac") -> OS.MACOS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            else -> OS.UNKNOWN
        }
    }

    /**
     * Валидация нативной библиотеки по суффиксу платформы.
     * @return `true` если библиотека нейтральна или соответствует текущей ОС.
     */
    private fun isLibraryCompatibleWithOs(path: String, os: OS): Boolean {
        val fileName = path.lowercase()
        if (!fileName.contains("natives")) return true
        
        return when (os) {
            OS.LINUX -> !fileName.contains("natives-windows") && !fileName.contains("natives-macos")
            OS.WINDOWS -> !fileName.contains("natives-linux") && !fileName.contains("natives-macos")
            OS.MACOS -> !fileName.contains("natives-windows") && !fileName.contains("natives-linux")
            else -> true
        }
    }
}